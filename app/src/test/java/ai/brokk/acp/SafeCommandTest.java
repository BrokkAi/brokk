package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafeCommandTest {

    @Test
    void simpleSafeNamesAreApproved() {
        assertTrue(SafeCommand.isKnownSafe("ls"));
        assertTrue(SafeCommand.isKnownSafe("cat"));
        assertTrue(SafeCommand.isKnownSafe("pwd"));
        assertTrue(SafeCommand.isKnownSafe("whoami"));
        assertTrue(SafeCommand.isKnownSafe("ls -la"));
        assertTrue(SafeCommand.isKnownSafe("cat README.md"));
        assertTrue(SafeCommand.isKnownSafe("grep -R foo src"));
        assertTrue(SafeCommand.isKnownSafe("head -n 10 file.txt"));
    }

    @Test
    void absolutePathExecutablesAreApproved() {
        assertTrue(SafeCommand.isKnownSafe("/usr/bin/ls"));
        assertTrue(SafeCommand.isKnownSafe("/bin/cat /etc/hosts"));
    }

    @Test
    void unknownExecutablesAreRejected() {
        assertFalse(SafeCommand.isKnownSafe("foo"));
        assertFalse(SafeCommand.isKnownSafe("rm foo"));
        assertFalse(SafeCommand.isKnownSafe("mvn test"));
        assertFalse(SafeCommand.isKnownSafe("npm install"));
        assertFalse(SafeCommand.isKnownSafe("cargo check"));
    }

    @Test
    void emptyAndWhitespaceAreRejected() {
        assertFalse(SafeCommand.isKnownSafe(""));
        assertFalse(SafeCommand.isKnownSafe("   "));
    }

    @Test
    void shellMetacharsRejected() {
        assertFalse(SafeCommand.isKnownSafe("ls $(whoami)"));
        assertFalse(SafeCommand.isKnownSafe("ls `whoami`"));
        assertFalse(SafeCommand.isKnownSafe("ls > out.txt"));
        assertFalse(SafeCommand.isKnownSafe("ls < input"));
        assertFalse(SafeCommand.isKnownSafe("(ls)"));
        assertFalse(SafeCommand.isKnownSafe("{ ls; }"));
        assertFalse(SafeCommand.isKnownSafe("ls &"));
        assertFalse(SafeCommand.isKnownSafe("ls\\ foo"));
    }

    @Test
    void doubleQuotesWithEscapesRejected() {
        assertFalse(SafeCommand.isKnownSafe("echo \"hello\\nworld\""));
        assertFalse(SafeCommand.isKnownSafe("echo \"$(whoami)\""));
        assertFalse(SafeCommand.isKnownSafe("echo \"`whoami`\""));
    }

    @Test
    void singleQuotedTokensAreAllowed() {
        assertTrue(SafeCommand.isKnownSafe("echo 'hello world'"));
        assertTrue(SafeCommand.isKnownSafe("grep -R 'pattern' src"));
    }

    @Test
    void doubleQuotedSimpleTokensAreAllowed() {
        assertTrue(SafeCommand.isKnownSafe("echo \"hello world\""));
        assertTrue(SafeCommand.isKnownSafe("grep -R \"Cargo.toml\" src"));
    }

    @Test
    void operatorChainsOfSafeCommandsAreApproved() {
        assertTrue(SafeCommand.isKnownSafe("ls && pwd"));
        assertTrue(SafeCommand.isKnownSafe("ls || true"));
        assertTrue(SafeCommand.isKnownSafe("echo hi ; ls"));
        assertTrue(SafeCommand.isKnownSafe("ls | wc -l"));
        assertTrue(SafeCommand.isKnownSafe("ls && pwd && whoami"));
    }

    @Test
    void operatorChainWithUnsafeSubcommandRejected() {
        assertFalse(SafeCommand.isKnownSafe("ls && rm -rf /"));
        assertFalse(SafeCommand.isKnownSafe("ls || rm foo"));
        assertFalse(SafeCommand.isKnownSafe("ls ; mvn test"));
    }

    @Test
    void bashLcWrapperIsApproved() {
        assertTrue(SafeCommand.isKnownSafe("bash -lc \"ls\""));
        assertTrue(SafeCommand.isKnownSafe("bash -lc \"ls && pwd\""));
        assertTrue(SafeCommand.isKnownSafe("sh -c \"git status\""));
    }

    @Test
    void bashLcWrapperWithUnsafeContentRejected() {
        assertFalse(SafeCommand.isKnownSafe("bash -lc \"rm -rf /\""));
        assertFalse(SafeCommand.isKnownSafe("bash -lc \"ls && rm foo\""));
    }

    @Test
    void nestedBashLcRejected() {
        assertFalse(SafeCommand.isKnownSafe("bash -lc \"bash -lc 'ls'\""));
    }

    @Test
    void findWithoutUnsafeFlagsIsApproved() {
        assertTrue(SafeCommand.isKnownSafe("find . -name foo.txt"));
        assertTrue(SafeCommand.isKnownSafe("find src -type f"));
    }

    @Test
    void findWithUnsafeFlagsRejected() {
        assertFalse(SafeCommand.isKnownSafe("find . -delete"));
        assertFalse(SafeCommand.isKnownSafe("find . -exec rm '{}' ;"));
        assertFalse(SafeCommand.isKnownSafe("find . -execdir true ;"));
        assertFalse(SafeCommand.isKnownSafe("find . -fls /tmp/log"));
    }

    @Test
    void ripgrepWithSafeFlagsIsApproved() {
        assertTrue(SafeCommand.isKnownSafe("rg -n pattern"));
        assertTrue(SafeCommand.isKnownSafe("rg --files-with-matches pattern src"));
    }

    @Test
    void ripgrepWithUnsafeFlagsRejected() {
        assertFalse(SafeCommand.isKnownSafe("rg --pre pwned files"));
        assertFalse(SafeCommand.isKnownSafe("rg --pre=pwned files"));
        assertFalse(SafeCommand.isKnownSafe("rg --hostname-bin pwned files"));
        assertFalse(SafeCommand.isKnownSafe("rg --search-zip files"));
        assertFalse(SafeCommand.isKnownSafe("rg -z files"));
    }

    @Test
    void gitReadOnlySubcommandsApproved() {
        assertTrue(SafeCommand.isKnownSafe("git status"));
        assertTrue(SafeCommand.isKnownSafe("git log"));
        assertTrue(SafeCommand.isKnownSafe("git diff"));
        assertTrue(SafeCommand.isKnownSafe("git show HEAD"));
        assertTrue(SafeCommand.isKnownSafe("git branch"));
        assertTrue(SafeCommand.isKnownSafe("git branch --show-current"));
        assertTrue(SafeCommand.isKnownSafe("git -C . status"));
    }

    @Test
    void gitMutatingSubcommandsRejected() {
        assertFalse(SafeCommand.isKnownSafe("git push"));
        assertFalse(SafeCommand.isKnownSafe("git pull"));
        assertFalse(SafeCommand.isKnownSafe("git fetch"));
        assertFalse(SafeCommand.isKnownSafe("git checkout main"));
        assertFalse(SafeCommand.isKnownSafe("git commit -m foo"));
        assertFalse(SafeCommand.isKnownSafe("git branch -d feature"));
        assertFalse(SafeCommand.isKnownSafe("git branch newbranch"));
    }

    @Test
    void gitGlobalConfigOverridesRejected() {
        assertFalse(SafeCommand.isKnownSafe("git -c core.pager=cat log"));
        assertFalse(SafeCommand.isKnownSafe("git --git-dir=.evil log"));
        assertFalse(SafeCommand.isKnownSafe("git --work-tree=. status"));
        assertFalse(SafeCommand.isKnownSafe("git --exec-path=.evil show"));
    }

    @Test
    void gitOutputFlagsRejected() {
        assertFalse(SafeCommand.isKnownSafe("git log --output=/tmp/log"));
        assertFalse(SafeCommand.isKnownSafe("git diff --ext-diff"));
        assertFalse(SafeCommand.isKnownSafe("git show --textconv HEAD"));
    }

    @Test
    void sedNumericPrintIsApproved() {
        assertTrue(SafeCommand.isKnownSafe("sed -n 1,5p file.txt"));
        assertTrue(SafeCommand.isKnownSafe("sed -n 10p file.txt"));
    }

    @Test
    void sedNonReadOnlyRejected() {
        assertFalse(SafeCommand.isKnownSafe("sed -i s/foo/bar/ file.txt"));
        assertFalse(SafeCommand.isKnownSafe("sed -n xp file.txt"));
        assertFalse(SafeCommand.isKnownSafe("sed s/foo/bar/ file.txt"));
    }

    @Test
    void base64OutputOptionsRejected() {
        assertFalse(SafeCommand.isKnownSafe("base64 -o out.bin"));
        assertFalse(SafeCommand.isKnownSafe("base64 --output out.bin"));
        assertFalse(SafeCommand.isKnownSafe("base64 --output=out.bin"));
        assertFalse(SafeCommand.isKnownSafe("base64 -ofile.bin"));
    }

    @Test
    void base64WithoutOutputApproved() {
        assertTrue(SafeCommand.isKnownSafe("base64"));
        assertTrue(SafeCommand.isKnownSafe("base64 -d input.txt"));
    }

    @Test
    void unbalancedQuotesRejected() {
        assertFalse(SafeCommand.isKnownSafe("echo 'unterminated"));
        assertFalse(SafeCommand.isKnownSafe("echo \"unterminated"));
    }

    @Test
    void mixedQuotingTokensRejected() {
        assertFalse(SafeCommand.isKnownSafe("echo abc'def'"));
        assertFalse(SafeCommand.isKnownSafe("echo 'abc'def"));
    }

    @Test
    void quotedExecutableNameRejected() {
        assertFalse(SafeCommand.isKnownSafe("'git status'"));
        assertFalse(SafeCommand.isKnownSafe("\"ls -la\""));
    }
}
