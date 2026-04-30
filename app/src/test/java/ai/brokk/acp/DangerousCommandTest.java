package ai.brokk.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DangerousCommandTest {

    @Test
    void rmAlwaysDangerous() {
        assertTrue(DangerousCommand.isDangerous("rm foo.txt"));
        assertTrue(DangerousCommand.isDangerous("rm -rf /"));
        assertTrue(DangerousCommand.isDangerous("rm -f bar"));
        assertTrue(DangerousCommand.isDangerous("/bin/rm foo"));
    }

    @Test
    void mvAlwaysDangerous() {
        assertTrue(DangerousCommand.isDangerous("mv foo bar"));
        assertTrue(DangerousCommand.isDangerous("mv ./a ./b"));
    }

    @Test
    void networkCommandsAreDangerous() {
        assertTrue(DangerousCommand.isDangerous("curl https://example.com"));
        assertTrue(DangerousCommand.isDangerous("wget https://example.com/file"));
        assertTrue(DangerousCommand.isDangerous("ssh user@host"));
        assertTrue(DangerousCommand.isDangerous("scp foo user@host:bar"));
        assertTrue(DangerousCommand.isDangerous("rsync -av src/ dest/"));
    }

    @Test
    void privilegeEscalationDangerous() {
        assertTrue(DangerousCommand.isDangerous("sudo apt install foo"));
        assertTrue(DangerousCommand.isDangerous("su root"));
        assertTrue(DangerousCommand.isDangerous("doas pkg install foo"));
    }

    @Test
    void processControlDangerous() {
        assertTrue(DangerousCommand.isDangerous("kill 1234"));
        assertTrue(DangerousCommand.isDangerous("pkill node"));
        assertTrue(DangerousCommand.isDangerous("killall java"));
    }

    @Test
    void permissionsDangerous() {
        assertTrue(DangerousCommand.isDangerous("chmod 777 foo"));
        assertTrue(DangerousCommand.isDangerous("chown user:group foo"));
        assertTrue(DangerousCommand.isDangerous("chgrp wheel foo"));
    }

    @Test
    void gitMutatingSubcommandsDangerous() {
        assertTrue(DangerousCommand.isDangerous("git push"));
        assertTrue(DangerousCommand.isDangerous("git push origin main"));
        assertTrue(DangerousCommand.isDangerous("git pull"));
        assertTrue(DangerousCommand.isDangerous("git fetch"));
        assertTrue(DangerousCommand.isDangerous("git reset --hard HEAD"));
        assertTrue(DangerousCommand.isDangerous("git rebase main"));
        assertTrue(DangerousCommand.isDangerous("git merge feature"));
        assertTrue(DangerousCommand.isDangerous("git clean -fd"));
        assertTrue(DangerousCommand.isDangerous("git filter-branch foo"));
    }

    @Test
    void gitReadOnlySubcommandsNotDangerous() {
        assertFalse(DangerousCommand.isDangerous("git status"));
        assertFalse(DangerousCommand.isDangerous("git log"));
        assertFalse(DangerousCommand.isDangerous("git diff"));
        assertFalse(DangerousCommand.isDangerous("git show"));
        assertFalse(DangerousCommand.isDangerous("git branch"));
    }

    @Test
    void npmMutatingSubcommandsDangerous() {
        assertTrue(DangerousCommand.isDangerous("npm install"));
        assertTrue(DangerousCommand.isDangerous("npm install foo"));
        assertTrue(DangerousCommand.isDangerous("npm publish"));
        assertTrue(DangerousCommand.isDangerous("npm update"));
        assertTrue(DangerousCommand.isDangerous("pnpm add foo"));
        assertTrue(DangerousCommand.isDangerous("yarn install"));
    }

    @Test
    void cargoPublishingDangerous() {
        assertTrue(DangerousCommand.isDangerous("cargo publish"));
        assertTrue(DangerousCommand.isDangerous("cargo install ripgrep"));
        assertTrue(DangerousCommand.isDangerous("cargo yank foo"));
    }

    @Test
    void cargoBuildAndTestNotDangerous() {
        assertFalse(DangerousCommand.isDangerous("cargo build"));
        assertFalse(DangerousCommand.isDangerous("cargo test"));
        assertFalse(DangerousCommand.isDangerous("cargo check"));
    }

    @Test
    void pipInstallDangerous() {
        assertTrue(DangerousCommand.isDangerous("pip install requests"));
        assertTrue(DangerousCommand.isDangerous("pip3 uninstall foo"));
        assertTrue(DangerousCommand.isDangerous("uv install foo"));
    }

    @Test
    void shellMetacharsTreatedAsDangerous() {
        assertTrue(DangerousCommand.isDangerous("ls > out.txt"));
        assertTrue(DangerousCommand.isDangerous("cat < input"));
        assertTrue(DangerousCommand.isDangerous("ls $(whoami)"));
        assertTrue(DangerousCommand.isDangerous("ls `whoami`"));
        assertTrue(DangerousCommand.isDangerous("ls && rm foo"));
        assertTrue(DangerousCommand.isDangerous("ls || pwd"));
        assertTrue(DangerousCommand.isDangerous("ls; pwd"));
        assertTrue(DangerousCommand.isDangerous("ls | wc"));
    }

    @Test
    void emptyOrBlankIsDangerous() {
        assertTrue(DangerousCommand.isDangerous(""));
        assertTrue(DangerousCommand.isDangerous("   "));
    }

    @Test
    void typicalBuildCommandsNotDangerous() {
        assertFalse(DangerousCommand.isDangerous("mvn test"));
        assertFalse(DangerousCommand.isDangerous("mvn clean compile"));
        assertFalse(DangerousCommand.isDangerous("gradle build"));
        assertFalse(DangerousCommand.isDangerous("./gradlew test"));
        assertFalse(DangerousCommand.isDangerous("make"));
        assertFalse(DangerousCommand.isDangerous("python script.py"));
    }

    @Test
    void cpDangerousIfTouchingAbsolutePath() {
        assertTrue(DangerousCommand.isDangerous("cp foo /etc/passwd"));
        assertTrue(DangerousCommand.isDangerous("cp /tmp/x foo"));
        assertTrue(DangerousCommand.isDangerous("cp ~/file dest"));
    }

    @Test
    void cpRelativeNotDangerous() {
        assertFalse(DangerousCommand.isDangerous("cp src.txt dest.txt"));
        assertFalse(DangerousCommand.isDangerous("cp ./a ./b"));
    }
}
