package ai.brokk.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

public class HeadlessExecutorMainParseQueryParamsTest {

    @Test
    void decodesPercentAndPlus() {
        var params = HeadlessExecutorMain.parseQueryParams("q=hello%20world+test");
        assertEquals(Map.of("q", "hello world test"), params);
    }

    @Test
    void handlesEmptyValue() {
        var params = HeadlessExecutorMain.parseQueryParams("empty=");
        assertEquals(Map.of("empty", ""), params);
    }

    @Test
    void handlesMissingValue() {
        var params = HeadlessExecutorMain.parseQueryParams("novalue");
        assertEquals(Map.of("novalue", ""), params);
    }

    @Test
    void lastWriteWinsForDuplicateKeys() {
        var params = HeadlessExecutorMain.parseQueryParams("k=1&k=2");
        assertEquals(Map.of("k", "2"), params);
    }
}
