package io.github.jbellis.brokk.agents;

// Added import for Coder

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.github.jbellis.brokk.Llm;
import io.github.jbellis.brokk.ContextManager;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

/**
 * Agent responsible for identifying relevant test files for a given task.
 */
public class ValidationAgent {
    private static final Logger logger = LogManager.getLogger(ValidationAgent.class);
    private final ContextManager contextManager;
    private final StreamingChatLanguageModel quickModel;
    private final ExecutorService executor;

    // Constants for relevance markers
    private static final String RELEVANT_MARKER = "BRK_RELEVANT";
    private static final String IRRELEVANT_MARKER = "BRK_IRRELEVANT";
    // attempts for relevance check if LLM response is unclear
    private static final int MAX_RELEVANCE_TRIES = 3;

    public ValidationAgent(ContextManager contextManager, StreamingChatLanguageModel quickModel) {
        this.contextManager = contextManager;
        this.quickModel = quickModel;
        this.executor = contextManager.getBackgroundTasks();
    }

    /**
     * Finds test files relevant to the given instructions.
     *
     * @param instructions The instructions for the CodeAgent.
     * @return A list of ProjectFile objects representing the relevant test files.
     */
    public List<ProjectFile> execute(String instructions) throws InterruptedException {
        var allTestFiles = contextManager.getTestFiles();
        if (allTestFiles.isEmpty()) {
            logger.debug("No test files found in the project.");
            return List.of();
        }

        // Step 1: Initial filtering to get potentially relevant files
        var coder = contextManager.getLlm(quickModel, "TestAgent: " + instructions);
        var potentiallyRelevantFiles = getPotentiallyRelevantFiles(allTestFiles, instructions, coder);
        if (potentiallyRelevantFiles.isEmpty()) {
            logger.debug("Initial filtering found no potentially relevant test files.");
            return List.of();
        }
        logger.debug("Potentially relevant test files identified: {}", potentiallyRelevantFiles);

        // Step 2: Detailed relevance check in parallel
        var relevantFiles = checkFilesForRelevance(potentiallyRelevantFiles, instructions, coder);
        logger.debug("Confirmed relevant test files: {}", relevantFiles);

        return relevantFiles;
    }

    /**
     * Step 1: Asks the LLM to identify which test files from a list MIGHT be relevant.
     */
    private List<ProjectFile> getPotentiallyRelevantFiles(List<ProjectFile> allTestFiles, String instructions, Llm llm) throws InterruptedException {
        var filesText = allTestFiles.stream()
                .map(ProjectFile::toString)
                .collect(Collectors.joining("\n"));

        var systemMessage = """
                You are an assistant that identifies potentially relevant test files.
                Given a list of test files and instructions for a coding task, identify which files *may* contain tests relevant to implementing the instructions.
                List the full paths of the potentially relevant files, one per line. If none seem relevant, respond with "None".
                """.stripIndent();
        var userMessage = """
                <instructions>
                %s
                </instructions>
                
                <files>
                %s
                </files>
                
                Which of these test files might be relevant to testing the changes made to satisfy the instructions? List the full paths, one per line.
                """.formatted(instructions, filesText).stripIndent();

        // send the request
        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));
        logger.debug("Invoking quickModel via Coder for initial test file filtering. Prompt:\n{}", userMessage);
        var result = llm.sendRequest(messages);

        if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
            logger.warn("Error during initial test file filtering call: {}", result.error() != null ? result.error().getMessage() : "Empty response");
            return List.of(); // Return empty list on error
        }

        var llmResponse = result.chatResponse().aiMessage().text();
        logger.debug("Coder response for initial filtering:\n{}", llmResponse);

        return parseSuggestedFiles(llmResponse, allTestFiles);
    }

    /**
     * Parses the LLM response from step 1 to extract suggested file paths.
     * It compares the LLM response against the known list of files for robustness.
     */
    private static List<ProjectFile> parseSuggestedFiles(String llmResponse, List<ProjectFile> allTestFiles) {
        // Filter the known files based on whether their path appears in the LLM response
        return allTestFiles.stream().parallel()
                .filter(f -> llmResponse.contains(f.toString()))
                .toList();
    }

    /**
     * Step 2: Checks a list of potentially relevant files in parallel to confirm relevance using a parallel stream.
     */
    private List<ProjectFile> checkFilesForRelevance(List<ProjectFile> potentialFiles, String instructions, Llm llm) {
        // Use a parallel stream to check files for relevance concurrently
        return potentialFiles.stream().parallel()
                .map(file -> isFileRelevant(file, instructions, llm))
                .filter(result -> result.relevant)
                .map(result -> result.file)
                .collect(Collectors.toList());
    }

    /**
     * Represents the result of a single file relevance check.
     */
    private record RelevanceResult(ProjectFile file, boolean relevant) {
    }

    /**
     * Asks the LLM if a specific test file is relevant to the instructions, given its content.
     * Retries if the LLM response doesn't clearly indicate relevance or irrelevance.
     */
    private RelevanceResult isFileRelevant(ProjectFile file, String instructions, Llm llm) {
        String fileContent;
        try {
            // Use Files.readString to read content; handle potential IOException
            fileContent = Files.readString(file.absPath());
        } catch (IOException e) {
            logger.debug("Could not read content of test file: {}", file, e);
            return new RelevanceResult(file, false); // Assume irrelevant if unreadable
        }

        var systemMessage = """
                You are an assistant that determines if a test file is relevant to specific coding instructions.
                Evaluate the provided test file content as to its relevance to testing the changes made to satisfy the instructions.
                Think carefully about the code involved, and conclude with the marker '%s' if the file is relevant, or '%s' if it is not.
                You MUST include exactly one of these markers at the end of your response.
                """.formatted(RELEVANT_MARKER, IRRELEVANT_MARKER).stripIndent();
        var userMessage = """
                Instructions:
                %s
                
                Test File Path: %s
                
                Test File Content:
                ```
                %s
                ```
                
                Is this test file relevant to the instructions? Conclude your explanation with %s or %s.
                """.formatted(instructions, file, fileContent, RELEVANT_MARKER, IRRELEVANT_MARKER).stripIndent();
        var messages = List.of(new SystemMessage(systemMessage), new UserMessage(userMessage));

        for (int attempt = 1; attempt <= MAX_RELEVANCE_TRIES; attempt++) {
            logger.debug("Invoking quickModel via Coder for relevance check of file: {} (Attempt {}/{})", file, attempt, MAX_RELEVANCE_TRIES);
            // Use Coder to send the request
            Llm.StreamingResult result;
            try {
                result = llm.sendRequest(messages);
            } catch (InterruptedException e) {
                // normally we want to propagate request interruptions, but this is running in the parallel thread pool
                // so we don't expect to be interrupted
                throw new RuntimeException(e);
            }

            if (result.error() != null || result.chatResponse() == null || result.chatResponse().aiMessage() == null) {
                logger.debug("Error during relevance check call for {} (Attempt {}): {}", file, attempt, result.error() != null ? result.error().getMessage() : "Empty response");
                continue;
            }

            var llmResponse = result.chatResponse().aiMessage().text().strip();
            logger.debug("Coder response for relevance check of {} (Attempt {}): {}", file, attempt, llmResponse);

            // Check if the response contains exactly one of the markers
            boolean containsRelevant = llmResponse.contains(RELEVANT_MARKER);
            boolean containsIrrelevant = llmResponse.contains(IRRELEVANT_MARKER);

            if (containsRelevant && !containsIrrelevant) {
                return new RelevanceResult(file, true);
            } else if (!containsRelevant && containsIrrelevant) {
                return new RelevanceResult(file, false);
            }

            logger.warn("Ambiguous response for relevance check of {} (Attempt {}): {}", file, attempt, llmResponse);
            messages.add(result.chatResponse().aiMessage());
            messages.add(new UserMessage("You must respond with exactly one of the markers {%s, %s}".formatted(RELEVANT_MARKER, IRRELEVANT_MARKER)));
        }

        logger.debug("Still no answer after {} tries; assuming {} is not relevant", MAX_RELEVANCE_TRIES, file);
        return new RelevanceResult(file, false);
    }
}
