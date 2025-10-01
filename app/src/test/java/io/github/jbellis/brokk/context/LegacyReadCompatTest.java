package io.github.jbellis.brokk.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jbellis.brokk.context.FragmentDtos.AllFragmentsDto;
import io.github.jbellis.brokk.context.FragmentDtos.CompactContextDto;
import io.github.jbellis.brokk.context.FragmentDtos.FrozenFragmentDto;
import io.github.jbellis.brokk.testutil.NoOpConsoleIO;
import io.github.jbellis.brokk.testutil.TestContextManager;
import io.github.jbellis.brokk.util.HistoryIo;
import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies backward compatibility when reading legacy histories containing FrozenFragmentDto entries.
 */
public class LegacyReadCompatTest {

    @TempDir
    Path tempDir;

    @Test
    void testReadLegacyZipWithFrozenFragmentDto() throws Exception {
        var objectMapper = new ObjectMapper();

        // Build a tiny test image and bytes
        var w = 6;
        var h = 4;
        var bufferedImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = bufferedImage.createGraphics();
        g.setColor(Color.MAGENTA);
        g.fillRect(0, 0, w, h);
        g.dispose();

        var imageBytes = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "PNG", imageBytes);
        imageBytes.flush();
        var pngBytes = imageBytes.toByteArray();

        // Legacy FrozenFragmentDto for a pasted image (no text contentId needed)
        String fragId = "legacy-img-1";
        var frozenDto = new FrozenFragmentDto(
                fragId,
                ContextFragment.FragmentType.PASTE_IMAGE.name(),
                "Paste of legacy image",
                "pasted image",
                null,         // contentId (null because isTextFragment=false)
                false,        // isTextFragment
                "text/plain", // syntax style (unused for images)
                Set.of(),     // no files
                Fragments.AnonymousImageFragment.class.getName(),
                Map.of(),     // no meta required
                null          // repr
        );

        // fragments-v3.json data
        var allFragments = new AllFragmentsDto(
                3,
                Map.of(),                 // referenced
                Map.of(fragId, frozenDto),// virtual
                Map.of()                  // task
        );

        // Single context referencing the legacy frozen fragment in virtuals
        var ctxId = UUID.randomUUID().toString();
        var compactCtx = new CompactContextDto(
                ctxId,
                List.of(),               // editable
                List.of(),               // readonly (unused in V3)
                List.of(fragId),         // virtuals
                List.of(),               // tasks
                null,                    // parsedOutputId
                "Legacy load"
        );
        var contextsJsonl = objectMapper.writeValueAsString(compactCtx) + "\n";

        // Build the minimal legacy zip
        var zipFile = tempDir.resolve("legacy_frozen_v3.zip");
        try (var zos = new ZipOutputStream(java.nio.file.Files.newOutputStream(zipFile))) {
            // fragments-v3.json
            zos.putNextEntry(new ZipEntry("fragments-v3.json"));
            zos.write(objectMapper.writeValueAsBytes(allFragments));
            zos.closeEntry();

            // contexts.jsonl
            zos.putNextEntry(new ZipEntry("contexts.jsonl"));
            zos.write(contextsJsonl.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // images/<id>.png for legacy pasted image payload
            zos.putNextEntry(new ZipEntry("images/" + fragId + ".png"));
            zos.write(pngBytes);
            zos.closeEntry();
        }

        // Read using the loader under test
        var mgr = new TestContextManager(tempDir, new NoOpConsoleIO());
        var loadedHistory = HistoryIo.readZip(zipFile, mgr);

        assertNotNull(loadedHistory);
        assertEquals(1, loadedHistory.getHistory().size(), "Expected one context");

        var loadedCtx = loadedHistory.getHistory().get(0);
        var loadedFragOpt = loadedCtx
                .virtualFragments()
                .filter(f -> f.id().equals(fragId))
                .findFirst();

        assertTrue(loadedFragOpt.isPresent(), "Legacy frozen fragment should be present");
        var loadedFrag = loadedFragOpt.get();

        // Ensure the fragment behaves sensibly
        assertEquals(ContextFragment.FragmentType.PASTE_IMAGE, loadedFrag.getType());
        assertFalse(loadedFrag.isText());
        assertEquals("Paste of legacy image", loadedFrag.description());

        // Validate that image bytes were restored
        Image loadedImage = loadedFrag.image();
        assertNotNull(loadedImage);
        assertEquals(w, loadedImage.getWidth(null));
        assertEquals(h, loadedImage.getHeight(null));
    }
}
