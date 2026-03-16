package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextFragments;
import ai.brokk.git.GitDistance;
import ai.brokk.project.ModelProperties;
import ai.brokk.util.Json;
import ai.brokk.util.Lines;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Blocking;

/**
 * ReferenceAgent identifies files and symbols explicitly mentioned in a goal string
 * and classifies them to filter out coincidental matches.
 */
public class ReferenceAgent {
    private static final Logger logger = LogManager.getLogger(ReferenceAgent.class);

    private final IContextManager cm;

    public ReferenceAgent(IContextManager cm) {
        this.cm = cm;
    }

    @Blocking
    public Set<ContextFragment> resolveReferencedFragments(String goal, Context ctx) throws InterruptedException {
        Set<ProjectFile> fileCandidates = ConcurrentHashMap.newKeySet();
        Set<CodeUnit> classCandidates = ConcurrentHashMap.newKeySet();
        Set<CodeUnit> functionCandidates = ConcurrentHashMap.newKeySet();
        var identifierCounts = new ConcurrentHashMap<String, Integer>();

        IAnalyzer analyzer = cm.getAnalyzer();
        var lowerGoal = goal.toLowerCase(Locale.ROOT);

        // filenames
        cm.getProject().getAllFiles().parallelStream().forEach(file -> {
            String fileName = file.getFileName();
            if (Lines.containsBareToken(lowerGoal, fileName.toLowerCase(Locale.ROOT))) {
                fileCandidates.add(file);
            }
        });

        var allDeclarations = analyzer.getAllDeclarations();
        // class names
        allDeclarations.parallelStream().forEach(cu -> {
            if (!cu.isClass() || fileCandidates.contains(cu.source())) {
                return;
            }

            String target = cu.identifier();
            if (target.length() >= 3 && Lines.containsBareToken(lowerGoal, target.toLowerCase(Locale.ROOT))) {
                var signatureFree = cu.withoutSignature();
                if (classCandidates.add(signatureFree)) {
                    identifierCounts.merge(target, 1, Integer::sum);
                }
            }
        });

        // methods
        allDeclarations.parallelStream().forEach(cu -> {
            if (!cu.isFunction() || fileCandidates.contains(cu.source())) {
                return;
            }

            boolean isOrphanOrAlreadyHandled =
                    analyzer.parentOf(cu).map(classCandidates::contains).orElse(true);
            if (isOrphanOrAlreadyHandled) {
                return;
            }

            String target = cu.identifier();
            if (Lines.containsBareToken(lowerGoal, target.toLowerCase(Locale.ROOT))) {
                var signatureFree = cu.withoutSignature();
                if (functionCandidates.add(signatureFree)) {
                    identifierCounts.merge(target, 1, Integer::sum);
                }
            }
        });

        // remove common identifiers
        Set<String> whitelistedIdentifiers = identifierCounts.entrySet().stream()
                .filter(e -> e.getValue() <= 2)
                .map(Entry::getKey)
                .collect(Collectors.toSet());
        classCandidates.removeIf(cu -> !whitelistedIdentifiers.contains(cu.identifier()));
        functionCandidates.removeIf(cu -> !whitelistedIdentifiers.contains(cu.identifier()));

        Set<String> relevantReferences = classifyRelevantReferences(
                goal, ctx.historyOverview(), fileCandidates, classCandidates, functionCandidates);

        if (relevantReferences.isEmpty()) {
            return Set.of();
        }

        Set<ContextFragment> relevantFragments = new HashSet<>();
        for (var file : fileCandidates) {
            if (relevantReferences.contains(file.toString())) {
                relevantFragments.add(new ContextFragments.ProjectPathFragment(file, cm));
            }
        }
        for (var cu : classCandidates) {
            if (relevantReferences.contains(cu.fqName())) {
                relevantFragments.add(new ContextFragments.CodeFragment(cm, cu));
            }
        }
        for (var cu : functionCandidates) {
            if (relevantReferences.contains(cu.fqName())) {
                // force CF to re-resolve the CodeUnit since we've stripped its signature, direct lookup won't work
                relevantFragments.add(new ContextFragments.CodeFragment(cm, cu.fqName()));
            }
        }

        return relevantFragments;
    }

    private Set<String> classifyRelevantReferences(
            String goal,
            String conversationHistory,
            Set<ProjectFile> fileCandidates,
            Set<CodeUnit> classCandidates,
            Set<CodeUnit> memberCandidates)
            throws InterruptedException {
        LinkedHashMap<String, ProjectFile> references = new LinkedHashMap<>();

        fileCandidates.forEach(file -> references.putIfAbsent(file.toString(), file));
        classCandidates.forEach(cu -> references.putIfAbsent(cu.fqName(), cu.source()));
        memberCandidates.forEach(cu -> references.putIfAbsent(cu.fqName(), cu.source()));

        if (references.isEmpty()) {
            return Set.of();
        }

        List<String> candidates = new ArrayList<>(references.keySet());
        if (candidates.size() > 100) {
            var rankedFiles = GitDistance.sortByImportance(new HashSet<>(references.values()), cm.getRepo());
            var fileRanks = new HashMap<ProjectFile, Integer>(rankedFiles.size());
            for (int i = 0; i < rankedFiles.size(); i++) {
                fileRanks.put(rankedFiles.get(i), i);
            }

            candidates = candidates.stream()
                    .sorted(Comparator.comparingInt(
                                    (String ref) -> fileRanks.getOrDefault(references.get(ref), Integer.MAX_VALUE))
                            .thenComparing(ref -> ref))
                    .limit(100)
                    .toList();
        }

        var model = cm.getService().getModel(ModelProperties.ModelType.SUMMARIZE);
        Llm llm = cm.getLlm(new Llm.Options(model, "Classify referenced symbols/files", TaskResult.Type.SCAN));

        var schema = JsonSchema.builder()
                .name("ReferenceClassification")
                .rootElement(JsonObjectSchema.builder()
                        .addProperty(
                                "relevant",
                                JsonArraySchema.builder()
                                        .items(new JsonStringSchema())
                                        .build())
                        .required("relevant")
                        .additionalProperties(false)
                        .build())
                .build();

        var requestOptions = llm.requestOptions()
                .withResponseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(schema)
                        .build());

        String prompt = buildReferenceClassificationPrompt(goal, conversationHistory, candidates);
        var result = llm.sendRequest(List.of(new UserMessage(prompt)), requestOptions);
        if (result.error() != null) {
            return Set.of();
        }

        Set<String> candidateSet = new HashSet<>(candidates);
        Set<String> relevant = new HashSet<>(parseRelevantReferences(result.text()));
        relevant.removeIf(value -> !candidateSet.contains(value));
        return relevant;
    }

    private static String buildReferenceClassificationPrompt(
            String goal, String conversationHistory, List<String> references) {
        var mapper = Json.getMapper();
        var valuesArray = mapper.createArrayNode();
        references.forEach(valuesArray::add);

        return """
                The following filenames and identifiers were parsed from (partial) matches in the user's instructions.
                Return only the references that are relevant to the user's instructions, as informed by the conversation history.

                <conversation_history>
                %s
                </conversation_history>

                <current_instructions>
                %s
                </current_instructions>

                References:
                %s

                Output JSON with a single field:
                - "relevant": array of reference strings that are relevant.
                Include only relevant references in that array.
                """
                .formatted(conversationHistory, goal, valuesArray);
    }

    private static Set<String> parseRelevantReferences(String text) {
        try {
            var node = Json.getMapper().readTree(text);
            return Json.stringArrayToSet(node.path("relevant"));
        } catch (IOException e) {
            logger.debug("Failed to parse reference classification response: {}", text, e);
            return Set.of();
        }
    }
}
