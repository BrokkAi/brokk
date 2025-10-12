package io.github.jbellis.brokk.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileManagerUtilTest {

    @Test
    void buildWindowsCommand_forFile() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-win-file-");
        Path file = Files.createTempFile(dir, "file", ".txt");
        try {
            List<String> cmd = FileManagerUtil.buildWindowsCommand(file);
            assertEquals(List.of("explorer", "/select," + file.toAbsolutePath()), cmd);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void buildWindowsCommand_forDirectory() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-win-dir-");
        try {
            List<String> cmd = FileManagerUtil.buildWindowsCommand(dir);
            assertEquals(List.of("explorer", dir.toAbsolutePath().toString()), cmd);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void buildMacOsCommand_forFile() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-mac-file-");
        Path file = Files.createTempFile(dir, "file", ".txt");
        try {
            List<String> cmd = FileManagerUtil.buildMacOsCommand(file);
            assertEquals(List.of("open", "-R", file.toAbsolutePath().toString()), cmd);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void buildMacOsCommand_forDirectory() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-mac-dir-");
        try {
            List<String> cmd = FileManagerUtil.buildMacOsCommand(dir);
            assertEquals(List.of("open", dir.toAbsolutePath().toString()), cmd);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void resolveLinuxTargetPath_forFile_returnsParent() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-linux-file-");
        Path file = Files.createTempFile(dir, "file", ".txt");
        try {
            Path resolved = FileManagerUtil.resolveLinuxTargetPath(file);
            assertEquals(dir.toAbsolutePath(), resolved);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void resolveLinuxTargetPath_forDirectory_returnsSelf() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-linux-dir-");
        try {
            Path resolved = FileManagerUtil.resolveLinuxTargetPath(dir);
            assertEquals(dir.toAbsolutePath(), resolved);
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void buildLinuxXdgOpenCommand_usesResolvedPath() throws Exception {
        Path dir = Files.createTempDirectory("fmutil-linux-cmd-");
        Path file = Files.createTempFile(dir, "file", ".txt");
        try {
            List<String> cmdForFile = FileManagerUtil.buildLinuxXdgOpenCommand(file);
            assertEquals(List.of("xdg-open", dir.toAbsolutePath().toString()), cmdForFile);

            List<String> cmdForDir = FileManagerUtil.buildLinuxXdgOpenCommand(dir);
            assertEquals(List.of("xdg-open", dir.toAbsolutePath().toString()), cmdForDir);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
