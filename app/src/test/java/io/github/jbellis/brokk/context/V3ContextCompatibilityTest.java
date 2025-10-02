package io.github.jbellis.brokk.context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.IProject;
import io.github.jbellis.brokk.SessionManager;
import io.github.jbellis.brokk.analyzer.IAnalyzer;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.analyzer.Languages;
import io.github.jbellis.brokk.testutil.TestProject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the current SessionManager/Context model can still read and produce
 * the same export as the JSONL previously exported by ContextExplorer for v3 sessions.
 *
 * It compares the reconstructed JSONL (from the session zip) with the provided JSONL file
 * in test resources to ensure compatibility has not been broken.
 */
public class V3ContextCompatibilityTest {

    private static final String SESSION_UUID = "01999da2-fe37-7aca-ac39-03f621507439";

    private static final Gson gson = new Gson();

    @Test
    public void jsonlExportMatchesZip() throws Exception {
        // Resolve resources
        var cl = Thread.currentThread().getContextClassLoader();
        assertNotNull(cl, "Context ClassLoader is null");

        URL sessionsDirUrl = cl.getResource("sessions/v3");
        assertNotNull(sessionsDirUrl, "sessions/v3 directory not found in test resources");

        URL jsonlUrl = cl.getResource("sessions/v3/" + SESSION_UUID + ".jsonl");
        assertNotNull(jsonlUrl, "JSONL file not found in test resources");

        // Load expected exports from JSONL
        var expected = readJsonl(jsonlUrl);

        // Build actual exports from ZIP via SessionManager
        var sessionsDir = Path.of(sessionsDirUrl.toURI());
        var sessionManager = new SessionManager(sessionsDir);
        var contextManager = new TestMinimalContextManager();
        var sessionId = UUID.fromString(SESSION_UUID);

        var ch = sessionManager.loadHistory(sessionId, contextManager);
        assertNotNull(ch, "Session history could not be loaded from ZIP");

        List<ContextExportPojo> actual = new ArrayList<>();
        {
            int contextIndex = 1;
            for (var ctx : ch.getHistory()) {
                int historyEntries = ctx.getTaskHistory().size();
                int historyLines = countTaskHistoryLines(ctx);

                var ctxPojo = new ContextExportPojo();
                ctxPojo.contextIndex = contextIndex;
                ctxPojo.contextId = ctx.id().toString();
                ctxPojo.action = safeAction(ctx);
                ctxPojo.historyEntries = historyEntries;
                ctxPojo.historyLines = historyLines;
                ctxPojo.fragments = new ArrayList<>();

                // Fragments
                ctx.allFragments().forEach(f -> {
                    var frag = new FragmentExportPojo();
                    frag.id = f.id();
                    frag.type = f.getType().name();
                    frag.shortDescription = f.shortDescription();
                    frag.lineCount = safeIsText(f) ? safeLineCount(f) : 0;
                    frag.syntaxStyle = f.syntaxStyle();
                    ctxPojo.fragments.add(frag);
                });

                // Parsed output, if present
                var parsed = ctx.getParsedOutput();
                if (parsed != null) {
                    var frag = new FragmentExportPojo();
                    frag.id = parsed.id();
                    frag.type = parsed.getType().name();
                    frag.shortDescription = parsed.shortDescription();
                    frag.lineCount = safeIsText(parsed) ? safeLineCount(parsed) : 0;
                    frag.syntaxStyle = parsed.syntaxStyle();
                    ctxPojo.fragments.add(frag);
                }

                // Sort fragments deterministically by id (lexicographically)
                ctxPojo.fragments.sort(Comparator.comparing(f -> f.id));

                actual.add(ctxPojo);
                contextIndex++;
            }
        }

        // Compare expected vs actual
        assertEquals(expected.size(), actual.size(), "Context count mismatch");
        for (int i = 0; i < expected.size(); i++) {
            var eCtx = expected.get(i);
            var aCtx = actual.get(i);

            assertEquals(eCtx.contextIndex, aCtx.contextIndex, "contextIndex mismatch at " + i);
            assertEquals(eCtx.contextId, aCtx.contextId, "contextId mismatch at " + i);
            assertEquals(eCtx.action, aCtx.action, "action mismatch at " + i);
            assertEquals(eCtx.historyEntries, aCtx.historyEntries, "historyEntries mismatch at " + i);
            assertEquals(eCtx.historyLines, aCtx.historyLines, "historyLines mismatch at " + i);

            assertEquals(eCtx.fragments.size(), aCtx.fragments.size(), "fragment count mismatch at " + i);
            for (int j = 0; j < eCtx.fragments.size(); j++) {
                var eFrag = eCtx.fragments.get(j);
                var aFrag = aCtx.fragments.get(j);

                assertEquals(eFrag.id, aFrag.id, "fragment.id mismatch at " + i + "/" + j);
                assertEquals(eFrag.type, aFrag.type, "fragment.type mismatch at " + i + "/" + j);
                assertEquals(eFrag.shortDescription, aFrag.shortDescription, "fragment.shortDescription mismatch at " + i + "/" + j);
                assertEquals(eFrag.lineCount, aFrag.lineCount, "fragment.lineCount mismatch at " + i + "/" + j);
                assertEquals(eFrag.syntaxStyle, aFrag.syntaxStyle, "fragment.syntaxStyle mismatch at " + i + "/" + j);
            }
        }
    }

    private static List<ContextExportPojo> readJsonl(URL jsonlUrl) throws Exception {
        try (var in = jsonlUrl.openStream();
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var lines = reader.lines().filter(s -> !s.isBlank()).collect(Collectors.toList());
            List<ContextExportPojo> result = new ArrayList<>(lines.size());
            for (var line : lines) {
                var pojo = gson.fromJson(line, ContextExportPojo.class);
                if (pojo.fragments != null) {
                    pojo.fragments.sort(Comparator.comparing(f -> f.id));
                }
                result.add(pojo);
            }
            return result;
        }
    }

    

    private static String safeAction(Context ctx) {
        try {
            return ctx.getAction();
        } catch (Exception e) {
            return "(Summary Unavailable)";
        }
    }

    private static boolean safeIsText(ContextFragment f) {
        try {
            return f.isText();
        } catch (Exception e) {
            return true;
        }
    }

    private static int safeLineCount(ContextFragment f) {
        try {
            var t = f.text();
            return t.isEmpty() ? 0 : (int) t.lines().count();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int countTaskHistoryLines(Context ctx) {
        try {
            return ctx.getTaskHistory().stream()
                    .mapToInt(te -> {
                        try {
                            if (te.log() != null) {
                                var t = te.log().text();
                                return t.isEmpty() ? 0 : (int) t.lines().count();
                            } else if (te.summary() != null) {
                                var s = te.summary();
                                return s.isEmpty() ? 0 : (int) s.lines().count();
                            }
                        } catch (Exception e) {
                            // ignore and treat as 0
                        }
                        return 0;
                    })
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    // POJOs to match the JSONL schema from ContextExplorer export
    private static final class ContextExportPojo {
        int contextIndex;
        String contextId;
        String action;
        int historyEntries;
        int historyLines;
        List<FragmentExportPojo> fragments;
    }

    private static final class FragmentExportPojo {
        String id;
        String type;
        String shortDescription;
        int lineCount;
        String syntaxStyle;
    }

    /**
     * Minimal, self-contained context manager for loading history in tests.
     * Mirrors the logic used in ContextExplorer.MinimalContextManager closely enough for history loading.
     */
    private static final class TestMinimalContextManager implements IContextManager {
        private final IProject project;
        private final IAnalyzer analyzer = new IAnalyzer() {};

        TestMinimalContextManager() {
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            this.project = new TestProject(root, Languages.JAVA);
        }

        @Override
        public IProject getProject() {
            return project;
        }

        @Override
        public IAnalyzer getAnalyzer() {
            return analyzer;
        }

        @Override
        public IAnalyzer getAnalyzerUninterrupted() {
            return analyzer;
        }

        @Override
        public ProjectFile toFile(String relName) {
            Path root = project.getRoot();
            Path p = Path.of(relName).toAbsolutePath().normalize();
            Path rel;
            if (p.startsWith(root)) {
                rel = root.relativize(p);
            } else {
                Path candidate = Path.of(relName);
                rel = candidate.isAbsolute() ? candidate.getFileName() : candidate.normalize();
            }
            return new ProjectFile(root, rel);
        }

        
    }
}
