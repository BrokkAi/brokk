package ai.brokk;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ScreenScaleDetectionTest {

    static class FakeProvider implements SystemScaleProvider {
        @Nullable
        private Double graphicsScale = null;

        @Nullable
        private Integer toolkitDpi = null;

        @Nullable
        private List<String> lastCommandResult = null;

        FakeProvider() {}

        FakeProvider graphicsScale(@Nullable Double s) {
            this.graphicsScale = s;
            return this;
        }

        FakeProvider toolkitDpi(@Nullable Integer d) {
            this.toolkitDpi = d;
            return this;
        }

        FakeProvider commandResult(@Nullable List<String> out) {
            this.lastCommandResult = out;
            return this;
        }

        @Override
        public @Nullable Double getGraphicsConfigScale() {
            return graphicsScale;
        }

        @Override
        public @Nullable Integer getToolkitDpi() {
            return toolkitDpi;
        }

        @Override
        public @Nullable List<String> runCommand(String... command) {
            return lastCommandResult;
        }
    }

    @Test
    void tryDetectScaleViaKscreenDoctor_primaryPresent() {
        var lines = Arrays.asList(
                "Output: 0",
                "  Connector: HDMI-A-1",
                "  Scale: 1.0",
                "Output: 1",
                "  Connector: DP-1",
                "  Primary: true",
                "  Scale: 1.5");
        var provider = new FakeProvider().commandResult(lines);

        var raw = SystemScaleDetector.tryDetectScaleViaKscreenDoctor(provider);
        assertNotNull(raw);
        assertEquals(1.5, raw, 1e-9);

        var normalized = SystemScaleDetector.detectLinuxUiScale(provider);
        assertNotNull(normalized);
        // 1.5 rounds to 2
        assertEquals(2.0, normalized, 1e-9);
    }

    @Test
    void tryDetectScaleViaKscreenDoctor_noPrimary_takeFirst() {
        var lines = Arrays.asList(
                "Output: 0", "  Connector: HDMI-A-1", "  Scale: 2.0", "Output: 1", "  Connector: DP-1", "  Scale: 1.0");
        var provider = new FakeProvider().commandResult(lines);

        var raw = SystemScaleDetector.tryDetectScaleViaKscreenDoctor(provider);
        assertNotNull(raw);
        assertEquals(2.0, raw, 1e-9);

        var normalized = SystemScaleDetector.detectLinuxUiScale(provider);
        assertEquals(2.0, normalized, 1e-9);
    }

    @Test
    void tryDetectScaleViaKscreenDoctor_malformed_returnsNull() {
        var lines = Arrays.asList(
                "Output: 0",
                "  Connector: HDMI-A-1",
                "  SomeOtherLine: yes",
                "Output: 1",
                "  Connector: DP-1",
                "  Primary: maybe");
        var provider = new FakeProvider().commandResult(lines);

        var raw = SystemScaleDetector.tryDetectScaleViaKscreenDoctor(provider);
        assertNull(raw);
    }

    @Test
    void tryDetectScaleViaGsettings_integerParsing() {
        var provider = new FakeProvider().commandResult(Arrays.asList("2"));

        var detected = SystemScaleDetector.tryDetectScaleViaGsettings(provider);
        assertNotNull(detected);
        assertEquals(2.0, detected, 1e-9);
    }

    @Test
    void tryDetectScaleViaGsettings_uint32Prefix() {
        var provider = new FakeProvider().commandResult(Arrays.asList("uint32 2"));

        var detected = SystemScaleDetector.tryDetectScaleViaGsettings(provider);
        assertNotNull(detected);
        assertEquals(2.0, detected, 1e-9);
    }

    @Test
    void tryDetectScaleViaGsettings_nonNumeric_returnsNull() {
        var provider = new FakeProvider().commandResult(Arrays.asList("'some string'"));

        var detected = SystemScaleDetector.tryDetectScaleViaGsettings(provider);
        assertNull(detected);
    }

    @Test
    void tryDetectScaleViaWindows_graphicsTransform() {
        var provider = new FakeProvider().graphicsScale(1.25);

        var detected = SystemScaleDetector.tryDetectScaleViaWindows(provider);
        assertNotNull(detected);
        assertEquals(1.25, detected, 1e-9);
    }

    @Test
    void tryDetectScaleViaWindows_toolkitDpi() {
        var provider = new FakeProvider().graphicsScale(null).toolkitDpi(144);

        var detected = SystemScaleDetector.tryDetectScaleViaWindows(provider);
        assertNotNull(detected);
        assertEquals(1.5, detected, 1e-9);
    }

    @Test
    void tryDetectScaleViaWindows_registryDecimalAndHex() {
        var providerDecimal = new FakeProvider().commandResult(Arrays.asList("    AppliedDPI    REG_DWORD    96"));
        var detectedDecimal = SystemScaleDetector.tryDetectScaleViaWindows(providerDecimal);
        assertNotNull(detectedDecimal);
        assertEquals(1.0, detectedDecimal, 1e-9);

        var providerHex = new FakeProvider().commandResult(Arrays.asList("    AppliedDPI    REG_DWORD    0x60"));
        var detectedHex = SystemScaleDetector.tryDetectScaleViaWindows(providerHex);
        assertNotNull(detectedHex);
        assertEquals(1.0, detectedHex, 1e-9);
    }

    @Test
    void normalizeUiScaleToAllowed_edgeCases() {
        assertEquals(2.0, SystemScaleDetector.normalizeUiScaleToAllowed(2.4), 1e-9);
        assertEquals(5.0, SystemScaleDetector.normalizeUiScaleToAllowed(4.9), 1e-9);
        assertEquals(1.0, SystemScaleDetector.normalizeUiScaleToAllowed(0.9), 1e-9);
        assertEquals(3.0, SystemScaleDetector.normalizeUiScaleToAllowed(3.0), 1e-9);
    }

    @Test
    void tryDetectScaleViaGnomeDBus_variousOutputs() {
        // 1. Primary present with scale 2.0
        var provider1 = new FakeProvider().commandResult(List.of("'('(1, 1, 2.0, uint32 0, true, ...)"));
        var detected1 = SystemScaleDetector.tryDetectScaleViaGnomeDBus(provider1);
        assertNotNull(detected1);
        assertEquals(2.0, detected1, 1e-9);

        // 2. Primary monitor is second with scale 1.5
        var provider2 =
                new FakeProvider().commandResult(List.of("(..., false, ...), (0, 0, 1.5, uint32 0, true, ...)"));
        var detected2 = SystemScaleDetector.tryDetectScaleViaGnomeDBus(provider2);
        assertNotNull(detected2);
        assertEquals(1.5, detected2, 1e-9);

        // 3. No primary monitor
        var provider3 = new FakeProvider().commandResult(List.of("(..., false, ...)"));
        var detected3 = SystemScaleDetector.tryDetectScaleViaGnomeDBus(provider3);
        assertNull(detected3);

        // 4. Malformed scale value
        var provider4 = new FakeProvider().commandResult(List.of("(0, 0, 1..5, uint32 0, true, ...)"));
        var detected4 = SystemScaleDetector.tryDetectScaleViaGnomeDBus(provider4);
        assertNull(detected4);

        // 5. Empty output
        var provider5 = new FakeProvider().commandResult(List.of());
        var detected5 = SystemScaleDetector.tryDetectScaleViaGnomeDBus(provider5);
        assertNull(detected5);
    }
}
