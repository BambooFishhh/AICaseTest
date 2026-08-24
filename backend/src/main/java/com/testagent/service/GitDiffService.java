package com.testagent.service;

import com.testagent.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * v8.1: 本地 Git 仓库 diff 读取——为范围识别提供变更文件集与基线候选。
 * 仅读不写：对 git_url 项目复用 GitCloneService 的受管克隆，对 local_path 项目直接 -C 指向 .git。
 */
@Service
public class GitDiffService {

    private static final Logger log = LoggerFactory.getLogger(GitDiffService.class);

    private static final long GIT_TIMEOUT_SECONDS = 60;

    public boolean isGitRepo(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return false;
        }
        Path dotGit = Path.of(sourcePath.trim(), ".git");
        return Files.exists(dotGit);
    }

    /**
     * 基线候选：本地分支 / 远端分支 / tag / 当前 HEAD。
     */
    public Map<String, Object> listRefs(String sourcePath) {
        Path dir = requireRepo(sourcePath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", runGit(dir, List.of("rev-parse", "--abbrev-ref", "HEAD")));
        result.put("heads", forEachRef(dir, "refs/heads"));
        result.put("remotes", forEachRef(dir, "refs/remotes"));
        result.put("tags", forEachRef(dir, "refs/tags"));
        return result;
    }

    public List<Map<String, String>> diffFiles(String sourcePath, String baselineRef, String headRef) {
        Path dir = requireRepo(sourcePath);
        String head = headRef == null || headRef.isBlank() ? "HEAD" : headRef.trim();
        String baseline = baselineRef == null || baselineRef.isBlank()
                ? null : sanitizeRef(baselineRef.trim());

        // 三点 diff = 自基线分叉后本侧变更；基线缺失/无共同祖先时回退两点
        List<String> args = new ArrayList<>();
        args.add("diff");
        args.add("--name-status");
        if (baseline != null) {
            args.add(baseline + "..." + head);
        } else {
            args.add(head + "~1.." + head);
        }
        // v8.3fix: diff 输出不再套用 4000 字符截断——大变更集会被静默砍尾
        // （冒烟实测：4000 cap 只留到 doc/ 目录，后续 java 条目全部丢失）
        String output = tryRunGit(dir, args, 2_000_000);
        if (output == null && baseline != null) {
            log.info("[Scope] 三点 diff 失败，回退两点 diff: {}..{}", baseline, head);
            output = tryRunGit(dir, List.of("diff", "--name-status", baseline + ".." + head), 2_000_000);
        }
        if (output == null) {
            throw new BusinessException(50003, "git diff 执行失败，请确认仓库可用",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        List<Map<String, String>> files = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.trim().split("\t+");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0].substring(0, 1).toUpperCase();
            // R100 重命名取新路径（最后一个 tab 段）
            String path = normalize(parts[parts.length - 1]);
            if (path.isEmpty()) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("status", status);
            item.put("path", path);
            files.add(item);
        }
        long javaCount = files.stream().filter(f -> f.get("path").endsWith(".java")).count();
        log.info("[Scope] git diff {}...{}: {} 个变更文件（.java {} 个，原始输出 {} 字符）",
                baseline, head, files.size(), javaCount, output.length());
        return files;
    }

    private List<String> forEachRef(Path dir, String namespace) {
        List<String> refs = new ArrayList<>();
        try {
            String output = runGit(dir, List.of("for-each-ref", "--format=%(refname:short)", namespace));
            for (String line : output.split("\n")) {
                if (!line.isBlank()) {
                    refs.add(line.trim());
                }
            }
        } catch (BusinessException e) {
            log.warn("[Scope] 读取引用 {} 失败: {}", namespace, e.getMessage());
        }
        return refs;
    }

    private Path requireRepo(String sourcePath) {
        if (!isGitRepo(sourcePath)) {
            throw BusinessException.invalidParam(
                    "源码路径不是 Git 仓库（缺少 .git），无法自动识别本期范围；请改用包含 .git 的目录或使用手动标注模式");
        }
        return Path.of(sourcePath.trim()).toAbsolutePath().normalize();
    }

    /** 防参数注入：拒绝以 "-" 开头的引用名 */
    private String sanitizeRef(String ref) {
        if (ref.startsWith("-") || ref.contains("..\\") || ref.contains("\n")) {
            throw BusinessException.invalidParam("基线引用格式不正确: " + ref);
        }
        return ref.replace(' ', '_');
    }

    private String normalize(String path) {
        return path == null ? "" : path.trim().replace('\\', '/');
    }

    private String runGit(Path dir, List<String> args) {
        String out = tryRunGit(dir, args, 4000);
        if (out == null) {
            throw new BusinessException(50003, "git 命令执行失败，请确认已安装 git 且仓库可用",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return out;
    }

    /** maxChars: 输出保留上限（防超大输出撑爆内存）；diff 类调用传大值，引用列举用小值 */
    private String tryRunGit(Path dir, List<String> args, int maxChars) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < maxChars) {
                        output.append(line).append('\n');
                    }
                }
            }
            boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Scope] git 命令超时: {}", args);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("[Scope] git 命令退出码 {}: {} — {}", process.exitValue(), args, output);
                return null;
            }
            return output.toString().trim();
        } catch (IOException e) {
            log.warn("[Scope] git 命令不可用或无法执行: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
