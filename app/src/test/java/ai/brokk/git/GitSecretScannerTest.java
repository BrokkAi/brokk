package ai.brokk.git;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.Languages;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.testutil.TestAnalyzer;
import ai.brokk.testutil.TestConsoleIO;
import ai.brokk.testutil.TestContextManager;
import ai.brokk.testutil.TestProject;
import ai.brokk.tools.CodeQualityTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSecretScannerTest {

    @TempDir
    Path tempDir;

    private TestProject project;

    @AfterEach
    void tearDown() {
        if (project != null) {
            project.close();
        }
    }

    @Test
    void matcherDetectsProviderTokensAndPrivateKeys() {
        String text =
                """
                aws = "AKIAABCDEFGHIJKLMNOP"
                token = "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
                key = "-----BEGIN RSA PRIVATE KEY-----"
                """
                        .stripIndent();

        Set<GitSecretScanner.SecretKey> findings = GitSecretScanner.scanText("Secrets.java", text, false);

        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("AWS access key id")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("GitHub token")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("Private key block")), findings.toString());
    }

    @Test
    void matcherDetectsProviderTokensWithoutCredentialKeywords() {
        String text =
                """
                value = "AKIAABCDEFGHIJKLMNOP"
                value = "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
                value = "xoxb-1234567890-abcedfghij"
                value = "AIza12345678901234567890123456789012345"
                value = "sk_live_1234567890abcdef"
                """
                        .stripIndent();

        Set<GitSecretScanner.SecretKey> findings = GitSecretScanner.scanText("Config.java", text, false);

        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("AWS access key id")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("GitHub token")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("Slack token")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("Google API key")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("Stripe key")), findings.toString());
    }

    @Test
    void matcherDetectsGenericCredentialAssignment() {
        Set<GitSecretScanner.SecretKey> findings =
                GitSecretScanner.scanText("config.yml", "client_secret: qQ9xV7pL2mN8rT4sZ6wY", false);

        assertTrue(findings.stream().anyMatch(f -> f.rule().equals("Credential assignment")), findings.toString());
    }

    @Test
    void matcherDetectsCredentialAssignmentKeywordVariants() {
        String text =
                """
                apiKey = "qQ9xV7pL2mN8rT4sZ6wY"
                api_key = "aB9xV7pL2mN8rT4sZ6wY"
                api-key = "zZ9xV7pL2mN8rT4sZ6wY"
                access_key = "mM9xV7pL2mN8rT4sZ6wY"
                """
                        .stripIndent();

        Set<GitSecretScanner.SecretKey> findings = GitSecretScanner.scanText("config.yml", text, false);

        assertEquals(
                4,
                findings.stream()
                        .filter(f -> f.rule().equals("Credential assignment"))
                        .count(),
                findings.toString());
    }

    @Test
    void matcherIgnoresPlaceholdersAndLowConfidenceUnlessRequested() {
        String text =
                """
                password = "changeme"
                api_key = "test"
                token = "abcd123"
                """
                        .stripIndent();

        Set<GitSecretScanner.SecretKey> normalFindings = GitSecretScanner.scanText("config.yml", text, false);
        Set<GitSecretScanner.SecretKey> lowFindings = GitSecretScanner.scanText("config.yml", text, true);

        assertTrue(normalFindings.isEmpty(), normalFindings.toString());
        assertTrue(lowFindings.stream().anyMatch(f -> f.rule().equals("Credential-like name")), lowFindings.toString());
    }

    @Test
    void redactionDoesNotExposeFullSecret() {
        String secret = "qQ9xV7pL2mN8rT4sZ6wY";
        GitSecretScanner.SecretKey finding = GitSecretScanner.scanText("config.yml", "client_secret: " + secret, false)
                .iterator()
                .next();

        assertFalse(finding.sample().contains(secret), finding.sample());
        assertTrue(finding.sample().contains("qQ9x"), finding.sample());
        assertTrue(finding.sample().contains("Z6wY"), finding.sample());
    }

    @Test
    void toolReportsCurrentAndHistoryOnlyFindingsAndExcludesTests() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();
        try (GitRepo repo = new GitRepo(tempDir)) {
            project = new TestProject(tempDir, Languages.JAVA).withRepo(repo);
            TestAnalyzer analyzer = new TestAnalyzer(new java.util.ArrayList<>(), new java.util.HashMap<>(), project);
            var cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer, repo);
            var tools = new CodeQualityTools(cm);

            ProjectFile appFile = new ProjectFile(tempDir, "src/main/java/App.java");
            ProjectFile testFile = new ProjectFile(tempDir, "src/test/java/AppTest.java");
            Files.createDirectories(appFile.absPath().getParent());
            Files.createDirectories(testFile.absPath().getParent());
            appFile.write("class App { String client_secret = \"qQ9xV7pL2mN8rT4sZ6wY\"; }");
            testFile.write("class AppTest { String token = \"ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ\"; }");
            repo.add(appFile);
            repo.add(testFile);
            repo.getGit()
                    .commit()
                    .setSign(false)
                    .setMessage("add current secret")
                    .call();

            appFile.write("class App { String value = \"clean\"; }");
            repo.add(appFile);
            repo.getGit()
                    .commit()
                    .setSign(false)
                    .setMessage("remove current secret")
                    .call();

            ProjectFile liveFile = new ProjectFile(tempDir, "src/main/java/Live.java");
            liveFile.write("class Live { String token = \"ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIK\"; }");
            repo.add(liveFile);
            repo.getGit().commit().setSign(false).setMessage("add live secret").call();

            repo.invalidateCaches();
            String report = tools.reportSecretLikeCode(20, 20, true, false);

            assertTrue(report.contains("HISTORY"), report);
            assertTrue(report.contains("CURRENT") || report.contains("BOTH"), report);
            assertTrue(report.contains("src/main/java/App.java"), report);
            assertTrue(report.contains("src/main/java/Live.java"), report);
            assertFalse(report.contains("src/test/java/AppTest.java"), report);
            assertFalse(report.contains("qQ9xV7pL2mN8rT4sZ6wY"), report);
        }
    }

    @Test
    void toolReportsCachedDuplicateBlobAtEachPath() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();
        try (GitRepo repo = new GitRepo(tempDir)) {
            project = new TestProject(tempDir, Languages.JAVA).withRepo(repo);
            TestAnalyzer analyzer = new TestAnalyzer(new java.util.ArrayList<>(), new java.util.HashMap<>(), project);
            var cm = new TestContextManager(project, new TestConsoleIO(), Set.of(), analyzer, repo);
            var tools = new CodeQualityTools(cm);

            String text = "client_secret: qQ9xV7pL2mN8rT4sZ6wY";
            ProjectFile firstFile = new ProjectFile(tempDir, "src/main/resources/first.yml");
            ProjectFile secondFile = new ProjectFile(tempDir, "src/main/resources/second.yml");
            Files.createDirectories(firstFile.absPath().getParent());
            firstFile.write(text);
            secondFile.write(text);
            repo.add(firstFile);
            repo.add(secondFile);
            repo.getGit()
                    .commit()
                    .setSign(false)
                    .setMessage("add duplicate blob secrets")
                    .call();

            repo.invalidateCaches();
            String report = tools.reportSecretLikeCode(20, 5, false, false);

            assertTrue(report.contains("src/main/resources/first.yml"), report);
            assertTrue(report.contains("src/main/resources/second.yml"), report);
        }
    }

    @Test
    void historyScanDrainsBoundedInFlightWorkAcrossCommits() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call();
        try (GitRepo repo = new GitRepo(tempDir)) {
            project = new TestProject(tempDir, Languages.JAVA).withRepo(repo);

            ProjectFile earlyFile = writeResource("early.yml", "client_secret: qQ9xV7pL2mN8rT4sZ6wY");
            repo.add(earlyFile);
            repo.getGit().commit().setSign(false).setMessage("add early secret").call();

            ProjectFile duplicateOne = writeResource("duplicate-one.yml", "client_secret: aA9xV7pL2mN8rT4sZ6wY");
            ProjectFile duplicateTwo = writeResource("duplicate-two.yml", "client_secret: aA9xV7pL2mN8rT4sZ6wY");
            ProjectFile middleFile = writeResource("middle.yml", "client_secret: bB9xV7pL2mN8rT4sZ6wY");
            repo.add(duplicateOne);
            repo.add(duplicateTwo);
            repo.add(middleFile);
            repo.getGit()
                    .commit()
                    .setSign(false)
                    .setMessage("add duplicate and middle secrets")
                    .call();

            ProjectFile lateFile = writeResource("late.yml", "client_secret: cC9xV7pL2mN8rT4sZ6wY");
            repo.add(lateFile);
            repo.getGit().commit().setSign(false).setMessage("add late secret").call();

            repo.invalidateCaches();
            var report = new GitSecretScanner(repo).scan(20, true, false, 2);
            Set<String> paths = report.findings().stream()
                    .map(GitSecretScanner.SecretFinding::path)
                    .collect(Collectors.toSet());

            assertTrue(
                    paths.contains("src/main/resources/early.yml"),
                    report.findings().toString());
            assertTrue(
                    paths.contains("src/main/resources/middle.yml"),
                    report.findings().toString());
            assertTrue(
                    paths.contains("src/main/resources/late.yml"),
                    report.findings().toString());
            assertTrue(
                    paths.contains("src/main/resources/duplicate-one.yml"),
                    report.findings().toString());
            assertTrue(
                    paths.contains("src/main/resources/duplicate-two.yml"),
                    report.findings().toString());
        }
    }

    private ProjectFile writeResource(String fileName, String text) throws Exception {
        ProjectFile file = new ProjectFile(tempDir, "src/main/resources/" + fileName);
        Files.createDirectories(file.absPath().getParent());
        file.write(text);
        return file;
    }
}
