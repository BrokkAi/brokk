package ai.brokk.claudehook;

import ai.brokk.analyzer.Language;
import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.GitRepo;
import ai.brokk.git.GitRepoFactory;
import ai.brokk.git.IGitRepo;
import ai.brokk.git.LocalFileRepo;
import ai.brokk.project.IProject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * A minimal IProject implementation for the Claude Code plugin.
 *
 * Does NOT touch any global Brokk configuration directories, API keys,
 * session managers, dependency schedulers, or anything else from MainProject.
 * Just a git repo + file listing + language detection from file extensions.
 */
final class LightweightProject implements IProject {
    private final Path root;
    private final IGitRepo repo;
    private @Nullable Set<ProjectFile> filesCache;

    LightweightProject(Path root) {
        this.root = root.toAbsolutePath().normalize();
        this.repo = GitRepoFactory.hasGitRepo(this.root) ? new GitRepo(this.root) : new LocalFileRepo(this.root);
    }

    @Override
    public Path getRoot() {
        return root;
    }

    @Override
    public IGitRepo getRepo() {
        return repo;
    }

    @Override
    public boolean hasGit() {
        return repo instanceof GitRepo;
    }

    @Override
    public synchronized Set<ProjectFile> getAllFiles() {
        if (filesCache == null) {
            filesCache = Set.copyOf(repo.getFilesForAnalysis());
        }
        return filesCache;
    }

    @Override
    public Set<Language> getAnalyzerLanguages() {
        // Auto-detect from file extensions — same logic as MainProject but without
        // touching global config, project properties, or live dependencies
        Set<Language> detected = new HashSet<>();
        for (ProjectFile pf : getAllFiles()) {
            Language lang = Languages.fromExtension(pf.extension());
            if (lang != Languages.NONE) {
                detected.add(lang);
            }
        }
        return detected;
    }

    @Override
    public void close() {
        // nothing to close
    }
}
