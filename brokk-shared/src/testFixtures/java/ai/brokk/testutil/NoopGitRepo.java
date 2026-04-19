package ai.brokk.testutil;

import ai.brokk.analyzer.ProjectFile;
import ai.brokk.git.IGitRepo;
import java.util.Collection;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;

public final class NoopGitRepo implements IGitRepo {
    @Override
    public Set<ProjectFile> getTrackedFiles() {
        return Set.of();
    }

    @Override
    public void add(Collection<ProjectFile> files) throws GitAPIException {}

    @Override
    public void add(ProjectFile file) throws GitAPIException {}

    @Override
    public void remove(ProjectFile file) throws GitAPIException {}
}

