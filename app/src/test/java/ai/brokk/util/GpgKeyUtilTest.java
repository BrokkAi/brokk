package ai.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class GpgKeyUtilTest {

    @Test
    void testParseColonsOutput() {
        // Captured output format: gpg --list-secret-keys --with-colons (often combined with --fixed-list-mode)
        List<String> output = linesFrom(
                """
                tru::1:1715853123:0:3:1:5
                sec::4096:1:ABCDEF0123456789:1615853123:::-:::esSC:::::::
                fpr:::::::::A1B2C3D4E5F6G7H8I9J0K1L2ABCDEF0123456789:
                grp:::::::::D1D2D3D4D5D6D7D8D9D0D1D2D3D4D5D6D7D8D9D0:
                uid::::1615853123::9D6A5C3E2B1A0F4D::Brokk User <user@brokk.ai>::::::::::0:
                ssb::4096:1:9876543210FEDCBA:1615853123::::::esSC:::::::
                sec::2048:1:1122334455667788:1715853123:::-:::esSC:::::::
                uid::::1715853123::B8A7C6D5E4F3G2H1::Another Key <another@example.com>::::::::::0:
                """);

        List<GpgKeyUtil.GpgKey> keys = GpgKeyUtil.parseColonsOutput(output);

        assertEquals(2, keys.size());

        assertEquals("ABCDEF0123456789", keys.get(0).id());
        assertEquals("Brokk User <user@brokk.ai> (ABCDEF0123456789)", keys.get(0).displayName());

        assertEquals("1122334455667788", keys.get(1).id());
        assertEquals("Another Key <another@example.com> (1122334455667788)", keys.get(1).displayName());
    }

    @Test
    void testParseColonsOutput_NoUid() {
        List<String> output = linesFrom(
                """
                sec::4096:1:ABCDEF0123456789:1615853123:::-:::esSC:::::::
                """);

        List<GpgKeyUtil.GpgKey> keys = GpgKeyUtil.parseColonsOutput(output);

        assertEquals(1, keys.size());
        assertEquals("ABCDEF0123456789", keys.get(0).id());
        assertEquals("ABCDEF0123456789", keys.get(0).displayName());
    }

    @Test
    void testParseColonsOutput_MultipleUids_FirstWins() {
        List<String> output = linesFrom(
                """
                sec::4096:1:ABCDEF0123456789:1615853123:::-:::esSC:::::::
                uid::::1615853123::AAAAAAAAAAAAAAAA::Primary User <primary@example.com>::::::::::0:
                uid::::1615853123::BBBBBBBBBBBBBBBB::Secondary User <secondary@example.com>::::::::::0:
                """);

        List<GpgKeyUtil.GpgKey> keys = GpgKeyUtil.parseColonsOutput(output);

        assertEquals(1, keys.size());
        assertEquals("ABCDEF0123456789", keys.get(0).id());
        assertEquals("Primary User <primary@example.com> (ABCDEF0123456789)", keys.get(0).displayName());
    }

    private static List<String> linesFrom(String output) {
        return output.strip().lines().toList();
    }
}
