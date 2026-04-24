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
}
