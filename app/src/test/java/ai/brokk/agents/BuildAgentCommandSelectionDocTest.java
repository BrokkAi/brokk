package ai.brokk.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuildAgentCommandSelectionDocTest {

    @Test
    void testInterpolateMustacheTemplate_Files() {
        String template = "pytest {{#files}}{{value}} {{/files}}--junitxml=report.xml";
        List<String> files = List.of("tests/test_a.py", "tests/test_b.py");

        String result = BuildAgent.interpolateMustacheTemplate(template, files, "files");

        assertEquals("pytest tests/test_a.py tests/test_b.py --junitxml=report.xml", result);
    }

    @Test
    void testInterpolateMustacheTemplate_PythonVersion() {
        String template = "python{{pyver}} -m pytest";
        String result = BuildAgent.interpolateMustacheTemplate(template, List.of(), "unused", "3.11");

        assertEquals("python3.11 -m pytest", result);
    }

    @Test
    void testInterpolateMustacheTemplate_Empty() {
        assertEquals("", BuildAgent.interpolateMustacheTemplate("", List.of(), "files"));
    }
}
