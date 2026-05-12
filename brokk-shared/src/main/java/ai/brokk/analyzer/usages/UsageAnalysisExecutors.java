package ai.brokk.analyzer.usages;

import ai.brokk.concurrent.ExecutorsUtil;
import ai.brokk.concurrent.LoggingExecutorService;
import ai.brokk.util.ConcurrencyUtil;

public final class UsageAnalysisExecutors {
    private static final int USAGE_ANALYSIS_PARALLELISM = Runtime.getRuntime().availableProcessors();
    private static final int USAGE_ANALYSIS_IO_PARALLELISM = ConcurrencyUtil.computeAdaptiveIoConcurrencyCap();
    private static final LoggingExecutorService CPU_EXECUTOR =
            ExecutorsUtil.newFixedThreadExecutor("usage-analysis-cpu-", USAGE_ANALYSIS_PARALLELISM);
    private static final LoggingExecutorService IO_EXECUTOR =
            ExecutorsUtil.newVirtualThreadExecutor("usage-analysis-io-", USAGE_ANALYSIS_IO_PARALLELISM);

    private UsageAnalysisExecutors() {}

    public static LoggingExecutorService cpuExecutor() {
        return CPU_EXECUTOR;
    }

    public static LoggingExecutorService ioExecutor() {
        return IO_EXECUTOR;
    }
}
