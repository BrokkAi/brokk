package ai.brokk.analyzer.usages;

public record ReceiverTargetRef(
        String moduleSpecifier, String exportedName, boolean instanceReceiver, double confidence) {}
