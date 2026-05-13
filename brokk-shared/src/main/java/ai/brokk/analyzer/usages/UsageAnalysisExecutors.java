package ai.brokk.analyzer.usages;

import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.util.ConcurrencyUtil;

public final class UsageAnalysisExecutors {
    private static final int USAGE_ANALYSIS_PARALLELISM = Runtime.getRuntime().availableProcessors();
    private static final int USAGE_ANALYSIS_IO_PARALLELISM = ConcurrencyUtil.computeAdaptiveIoConcurrencyCap();
    private static final UsageAnalysisExecutorPool POOL = new UsageAnalysisExecutorPool(
            ExecutorsUtil.newFixedThreadExecutor("usage-analysis-cpu-", USAGE_ANALYSIS_PARALLELISM),
            ExecutorsUtil.newVirtualThreadExecutor("usage-analysis-io-", USAGE_ANALYSIS_IO_PARALLELISM));

    private UsageAnalysisExecutors() {}

    public static LoggingExecutorService cpuExecutor() {
        return POOL.cpuExecutor();
    }

    public static LoggingExecutorService ioExecutor() {
        return POOL.ioExecutor();
    }

    public record UsageAnalysisExecutorPool(LoggingExecutorService cpuExecutor, LoggingExecutorService ioExecutor) {}
}
