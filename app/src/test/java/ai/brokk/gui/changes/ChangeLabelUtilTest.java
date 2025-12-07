package ai.brokk.gui.changes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ChangeLabelUtilTest {

    @Test
    public void textFile_hasNoSuffix() {
        String label = ChangeLabelUtil.makeDisplayLabel("a.txt", ChangeFileStatus.TEXT);
        assertEquals("a.txt", label);
    }

    @Test
    public void binaryFile_appendsBinarySuffix() {
        String label = ChangeLabelUtil.makeDisplayLabel("image.png", ChangeFileStatus.BINARY);
        assertEquals("image.png (binary)", label);
    }

    @Test
    public void oversizedFile_appendsTooLargeSuffix() {
        String label = ChangeLabelUtil.makeDisplayLabel("huge.log", ChangeFileStatus.OVERSIZED);
        assertEquals("huge.log (too large)", label);
    }
}
