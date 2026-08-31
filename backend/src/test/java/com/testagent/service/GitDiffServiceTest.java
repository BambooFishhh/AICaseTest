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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v8.9.8: 默认基线自动探测——常规迭代基线即主干（免手选）。
 * 依赖真实 git 命令，不可用时跳过。
 */
class GitDiffServiceTest {

    private final GitDiffService service = new GitDiffService();

    @Test
    void detectsLocalMainWhenNoRemote(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git 命令不可用，跳过");

        Path repo = initRepo(tempDir, "main");
        assertEquals("main", service.detectDefaultBaseline(repo.toString()));
    }

    @Test
    void detectsLocalMasterWhenNoRemote(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git 命令不可用，跳过");

        Path repo = initRepo(tempDir, "master");
        assertEquals("master", service.detectDefaultBaseline(repo.toString()));
    }

    @Test
    void prefersOriginHeadOverLocalBranches(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git 命令不可用，跳过");

        Path origin = initRepo(tempDir.resolve("origin-dir"), "main");
        Path clone = tempDir.resolve("clone");
        runGit(tempDir, "clone", origin.toString(), clone.toString());
        // 克隆后 origin/HEAD 指向远端默认分支，应优先于本地分支名回退
        assertEquals("origin/main", service.detectDefaultBaseline(clone.toString()));
    }

    @Test
    void returnsNullWhenNoTrunkCandidates(@TempDir Path tempDir) throws Exception {
        assumeTrue(isGitAvailable(), "git 命令不可用，跳过");

        // 只有 feature 分支（无 master/main、无远端）→ 探测失败返回 null，由调用方要求手填
        Path repo = initRepo(tempDir, "feature-x");
        assertNull(service.detectDefaultBaseline(repo.toString()));
    }

    // ==================== 测试基建 ====================

    private Path initRepo(Path dir, String branch) throws Exception {
        Files.createDirectories(dir);
        runGit(dir, "init", "-b", branch);
        runGit(dir, "config", "user.email", "test@example.com");
        runGit(dir, "config", "user.name", "test");
        Files.writeString(dir.resolve("README.md"), "# demo\n", StandardCharsets.UTF_8);
        runGit(dir, "add", ".");
        runGit(dir, "commit", "-m", "init");
        assertTrue(Files.isDirectory(dir.resolve(".git")));
        return dir;
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
        assertTrue(process.exitValue() == 0, "git 命令执行失败: " + String.join(" ", args));
    }
}
