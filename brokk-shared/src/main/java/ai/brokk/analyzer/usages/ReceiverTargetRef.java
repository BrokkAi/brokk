package ai.brokk.analyzer.usages;

import ai.brokk.analyzer.ProjectFile;
import org.jetbrains.annotations.Nullable;

public record ReceiverTargetRef(
        @Nullable String moduleSpecifier,
        String exportedName,
        boolean instanceReceiver,
        double confidence,
        @Nullable ProjectFile localFile) {}
