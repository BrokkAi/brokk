package ai.brokk.agents;

import ai.brokk.IContextManager;
import ai.brokk.Llm;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    public Set<ContextFragment> resolveReferencedFragments(String goal) throws InterruptedException {
        Set<ProjectFile> fileCandidates = new HashSet<>();
        Set<CodeUnit> classCandidates = new HashSet<>();
        Set<CodeUnit> memberCandidates = new HashSet<>();

        IAnalyzer analyzer = cm.getAnalyzer();
        var lowerGoal = goal.toLowerCase(Locale.ROOT);

        // filenames
        for (var file : cm.getProject().getAllFiles()) {
            String fileName = file.getFileName();
            if (Lines.containsBareToken(lowerGoal, fileName.toLowerCase(Locale.ROOT))) {
                fileCandidates.add(file);
            }
        }

        var allDeclarations = analyzer.getAllDeclarations();
        // class names
        for (var cu : allDeclarations) {
            if (!cu.isClass() || fileCandidates.contains(cu.source())) {
                continue;
            }

            String target = cu.identifier();
            if (target.length() >= 3 && Lines.containsBareToken(lowerGoal, target.toLowerCase(Locale.ROOT))) {
                classCandidates.add(cu);
            }
        }

        // methods, fields
        for (var cu : allDeclarations) {
            if (!(cu.isFunction() || cu.isField()) || fileCandidates.contains(cu.source())) {
                continue;
            }

            boolean isOrphanOrAlreadyHandled =
                    analyzer.parentOf(cu).map(classCandidates::contains).orElse(true);
            if (isOrphanOrAlreadyHandled) {
                continue;
            }

            String target = cu.identifier();
            if (Lines.containsBareToken(lowerGoal, target.toLowerCase(Locale.ROOT))) {
                memberCandidates.add(cu);
            }
        }

        Set<String> relevantReferences =
                classifyRelevantReferences(goal, fileCandidates, classCandidates, memberCandidates);

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
        for (var cu : memberCandidates) {
            if (relevantReferences.contains(cu.fqName())) {
                relevantFragments.add(new ContextFragments.CodeFragment(cm, cu));
            }
        }

        return relevantFragments;
    }

    private Set<String> classifyRelevantReferences(
            String goal, Set<ProjectFile> fileCandidates, Set<CodeUnit> classCandidates, Set<CodeUnit> memberCandidates)
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

        Set<String> relevant = new HashSet<>();
        Set<String> remaining = new LinkedHashSet<>(candidates);

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
                        .addProperty(
                                "coincidental",
                                JsonArraySchema.builder()
                                        .items(new JsonStringSchema())
                                        .build())
                        .required("relevant", "coincidental")
                        .additionalProperties(false)
                        .build())
                .build();

        var requestOptions = llm.requestOptions()
                .withResponseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(schema)
                        .build());

        for (int i = 0; i < 3; i++) {
            List<String> batch = candidates.stream().filter(remaining::contains).toList();
            String prompt = buildReferenceClassificationPrompt(goal, batch);
            var result = llm.sendRequest(List.of(new UserMessage(prompt)), requestOptions);
            if (result.error() != null || result.text().isBlank()) {
                continue;
            }

            ReferenceClassificationResponse parsed = parseReferenceClassification(result.text());

            for (String value : parsed.relevant()) {
                if (remaining.remove(value)) {
                    relevant.add(value);
                }
            }
            for (String value : parsed.coincidental()) {
                remaining.remove(value);
            }

            if (remaining.isEmpty()) {
                break;
            }
        }

        return relevant;
    }

    private static String buildReferenceClassificationPrompt(String goal, List<String> references) {
        var mapper = Json.getMapper();
        var valuesArray = mapper.createArrayNode();
        references.forEach(valuesArray::add);

        return """
                The following filenames and identifiers were parsed from (partial) matches in the user's instructions.
                Classify each as relevant or coincidental for the user's goal.

                <user_instructions>
                %s
                </user_instructions>

                References:
                %s

                Every provided reference string must appear in exactly one array: "relevant" or "coincidental".
                """
                .formatted(goal, valuesArray);
    }

    private static ReferenceClassificationResponse parseReferenceClassification(String text) {
        try {
            var node = Json.getMapper().readTree(text);
            Set<String> relevant = Json.stringArrayToSet(node.path("relevant"));
            Set<String> coincidental = Json.stringArrayToSet(node.path("coincidental"));
            return new ReferenceClassificationResponse(relevant, coincidental);
        } catch (IOException e) {
            logger.debug("Failed to parse reference classification response: {}", text, e);
            return new ReferenceClassificationResponse(Set.of(), Set.of());
        }
    }

    private record ReferenceClassificationResponse(Set<String> relevant, Set<String> coincidental) {}
}
