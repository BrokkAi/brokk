package ai.brokk.tools;

public interface ToolOutput {
    String llmText();

    record TextOutput(String llmText) implements ToolOutput {}
}
