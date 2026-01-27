package ai.brokk.executor.jobs;

import ai.brokk.ContextManager;
import ai.brokk.IConsoleIO;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

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
        @Nullable StreamingChatModel scanModel) {}
