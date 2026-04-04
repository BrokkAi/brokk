package ai.brokk.tools;

import ai.brokk.AnalyzerWrapper;
import ai.brokk.NullAnalyzerListener;
import ai.brokk.agents.BuildAgent;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.MultiAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.Context;
import ai.brokk.project.MainProject;
import ai.brokk.watchservice.NoopWatchService;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MostRelevantFilesCli {
    private static final int LIMIT = 100;

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        List<String> seedPaths = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> {
                    if (i + 1 >= args.length) {
                        return fail("--root requires a path");
                    }
                    root = Path.of(args[++i]).toAbsolutePath().normalize();
                }
                case "--help", "-h" -> {
                    printHelp();
                    return 0;
                }
                default -> seedPaths.add(args[i]);
            }
        }

        if (seedPaths.isEmpty()) {
            printHelp();
            return 1;
        }

        try (var project = new MainProject(root)) {
            // This CLI is intended to rank explicit workspace paths, so persisted build exclusions
            // should not silently remove the seed files from the analyzer universe.
            project.setBuildDetails(BuildAgent.BuildDetails.EMPTY);
            overrideAnalyzerLanguagesIfRequested(project);

            var seedFiles = new ArrayList<ProjectFile>();
            var missing = new ArrayList<String>();

            for (var seedPath : seedPaths) {
                var file = new ProjectFile(root, seedPath);
                if (file.exists()) {
                    seedFiles.add(file);
                } else {
                    missing.add(seedPath);
                }
            }

            if (!missing.isEmpty()) {
                return fail("Seed files not found: " + String.join(", ", missing));
            }

            if (shouldStartFresh()) {
                deletePersistedAnalyzerStateForCli(project, seedFiles);
            }

            try (var analyzerWrapper =
                    new AnalyzerWrapper(project, new NullAnalyzerListener(), new NoopWatchService())) {
                var analyzer = supplementedAnalyzer(project, analyzerWrapper.get(), seedFiles);

                for (var file : Context.getMostRelevantFiles(project.getRepo(), analyzer, seedFiles, LIMIT)) {
                    System.out.println(file);
                }
                return 0;
            }
        }
    }

    private static int fail(String message) {
        System.err.println(message);
        return 1;
    }

    private static void printHelp() {
        System.out.println("Usage: ./gradlew :app:runMostRelevantFiles -Pargs='[--root PROJECT_ROOT] <seed-file>...'");
    }

    static IAnalyzer supplementedAnalyzer(MainProject project, IAnalyzer baseAnalyzer, List<ProjectFile> seedFiles) {
        var missingSeedLanguages = new LinkedHashSet<Language>();
        for (var seedFile : seedFiles) {
            var language = Languages.fromExtension(seedFile.extension());
            if (language != Languages.NONE && !baseAnalyzer.languages().contains(language)) {
                missingSeedLanguages.add(language);
            }
        }
        if (missingSeedLanguages.isEmpty()) {
            return baseAnalyzer;
        }

        Map<Language, IAnalyzer> delegates = new HashMap<>();
        for (var language : baseAnalyzer.languages()) {
            baseAnalyzer.subAnalyzer(language).ifPresent(subAnalyzer -> delegates.put(language, subAnalyzer));
        }
        for (var language : missingSeedLanguages) {
            var supplemental = language.createAnalyzer(project, IAnalyzer.ProgressListener.NOOP);
            if (!supplemental.isEmpty()) {
                delegates.put(language, supplemental);
            }
        }

        if (delegates.isEmpty()) {
            return baseAnalyzer;
        }
        if (delegates.size() == 1) {
            return delegates.values().iterator().next();
        }
        return new MultiAnalyzer(delegates);
    }

    /**
     * Keep this startup behavior in sync with bifrost's parity harness expectations.
     *
     * <p>The most-relevant-files CLI should rank against a fresh analyzer snapshot, not "load/repair stale cache,
     * then rebuild later." We therefore delete persisted analyzer state up front for the union of configured project
     * languages and explicit seed languages before constructing the AnalyzerWrapper.
     */
    static void deletePersistedAnalyzerStateForCli(MainProject project, List<ProjectFile> seedFiles) {
        Set<Language> languagesToDelete = new HashSet<>();
        languagesToDelete.addAll(project.getAnalyzerLanguages());
        for (var seedFile : seedFiles) {
            var language = Languages.fromExtension(seedFile.extension());
            if (language != Languages.NONE) {
                languagesToDelete.add(language);
            }
        }

        for (var language : languagesToDelete) {
            if (language == Languages.NONE) {
                continue;
            }
            try {
                Files.deleteIfExists(language.getStoragePath(project));
            } catch (IOException ignored) {
                // Fresh analyzer startup is best-effort; unreadable stale state should not abort CLI ranking.
            }
        }
    }

    static boolean shouldStartFresh() {
        return Boolean.getBoolean("brokk.mrf.fresh") || "1".equals(System.getenv("BROKK_MRF_FRESH"));
    }

    static void overrideAnalyzerLanguagesIfRequested(MainProject project) {
        String raw = System.getProperty("brokk.mrf.languages");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("BROKK_MRF_LANGUAGES");
        }
        if (raw == null || raw.isBlank()) {
            return;
        }

        Set<Language> languages = new LinkedHashSet<>();
        for (var token : Splitter.on(',').split(raw)) {
            var trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var language = Languages.valueOf(trimmed);
            if (language != Languages.NONE) {
                languages.add(language);
            }
        }
        if (!languages.isEmpty()) {
            project.setAnalyzerLanguages(languages);
        }
    }
}
