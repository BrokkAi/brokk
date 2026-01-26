package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import ai.brokk.executor.jobs.JobSpec;
import ai.brokk.executor.jobs.JobStore;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Immutable bundle of values needed during a job execution.
 *
 * This object centralizes the per-job data that was previously threaded through individual
 * runXxxMode(...) method parameters.
 */
public record JobExecutionContext(
        String jobId,
        JobSpec spec,
        ContextManager cm,
        JobStore store,
        IConsoleIO io,
        BooleanSupplier cancelled,
        @Nullable StreamingChatModel plannerModel,
        @Nullable StreamingChatModel codeModel,
        @Nullable StreamingChatModel scanModel) {
}
