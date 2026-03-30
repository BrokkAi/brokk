package ai.brokk.analyzer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.brokk.testutil.InlineTestProjectCreator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypescriptDecoratorTest {

    @Test
    void testTypescriptAnalyzer_ExtractsComponentAttributes() {
        String tsCode =
                """
                import { Component } from '@angular/core';

                @Component({
                  selector: 'app-root',
                  templateUrl: './app.component.html'
                })
                export class AppComponent {}
                """;

        try (var project = InlineTestProjectCreator.empty()
                .addFileContents(tsCode, "app.component.ts")
                .build()) {

            var analyzer = new TypescriptAnalyzer(project);

            var state = analyzer.snapshotState();

            // Find AppComponent CodeUnit
            var appComponentCu = state.codeUnitState().keySet().stream()
                    .filter(cu -> cu.shortName().equals("AppComponent"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("AppComponent not found"));

            var attributes = state.codeUnitState().get(appComponentCu).attributes();

            assertTrue(attributes.containsKey("angular.component"), "Should have angular.component attribute");

            @SuppressWarnings("unchecked")
            var componentData = (Map<String, Object>) attributes.get("angular.component");

            assertEquals("./app.component.html", componentData.get("templateUrl"));
        }
    }
}
