package com.testagent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitCloneServiceTest {

    @Test
    void rejectsUnsupportedUrls() {
        GitCloneService service = new GitCloneService();

        assertFalse(service.isValidGitUrl(null));
        assertFalse(service.isValidGitUrl(""));
        assertFalse(service.isValidGitUrl("file:///tmp/repo"));
        assertFalse(service.isValidGitUrl("C:/repo"));
        assertFalse(service.isValidGitUrl("https://host/repo\nset"));
    }

    @Test
    void acceptsCommonCloneUrls() {
        GitCloneService service = new GitCloneService();

        assertTrue(service.isValidGitUrl("https://github.com/org/repo.git"));
        assertTrue(service.isValidGitUrl("https://github.com/org/repo"));
        assertTrue(service.isValidGitUrl("ssh://git@github.com/org/repo.git"));
        assertTrue(service.isValidGitUrl("git@github.com:org/repo.git"));
        assertTrue(service.isValidGitUrl("git://github.com/org/repo.git"));
    }

    @Test
    void clonesLocalRepository(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git 命令不可用，跳过真实克隆测试");

        Path source = tempDir.resolve("source");
        Files.createDirectories(source);
        runGit(source, "init", "-b", "main");
        runGit(source, "config", "user.email", "test@example.com");
        runGit(source, "config", "user.name", "test");
        Files.writeString(source.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        runGit(source, "add", ".");
        runGit(source, "commit", "-m", "init");

        GitCloneService service = new GitCloneService();
        Path target = tempDir.resolve("clones").resolve("p1");
        Path cloned = service.cloneToTarget(source.toString(), target);

        assertTrue(Files.isRegularFile(cloned.resolve("README.md")));
        assertTrue(Files.isDirectory(cloned.resolve(".git")));
    }

    private boolean isGitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void runGit(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // drain process output
            }
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "git 命令执行超时");
        assertTrue(process.exitValue() == 0, "git 命令执行失败");
    }
}
