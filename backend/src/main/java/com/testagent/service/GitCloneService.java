package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.common.SafeDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git URL 项目创建时执行真实 clone，克隆到受管目录后按本地源码路径接入现有分析流程。
 */
@Service
public class GitCloneService {

    private static final Logger log = LoggerFactory.getLogger(GitCloneService.class);
    private static final Pattern GIT_URL_PATTERN = Pattern.compile(
            "^(https?|ssh|git)://\\S+$|^git@\\S+:.+$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_URL_LENGTH = 2048;

    @Value("${app.git.clone-dir:data/git-repos}")
    private String cloneDir = "data/git-repos";

    @Value("${app.git.clone-timeout-seconds:600}")
    private long cloneTimeoutSeconds = 600;

    // v8.5: DNS 安全解析统一走 SafeDnsResolver（字段初始化兜底，直 new 的单测不受影响）
    private SafeDnsResolver safeDnsResolver = new SafeDnsResolver();

    @Autowired(required = false)
    void setSafeDnsResolver(SafeDnsResolver safeDnsResolver) {
        this.safeDnsResolver = safeDnsResolver;
    }

    public boolean isGitUrl(String sourceType, String sourcePath) {
        return "git_url".equals(sourceType) && isValidGitUrl(sourcePath);
    }

    public String clone(String url, String projectId) {
        String normalized = (url == null ? "" : url.trim());
        if (!isValidGitUrl(normalized)) {
            throw BusinessException.invalidParam("Git 地址格式不正确，仅支持 http/https/ssh/git 地址");
        }
        // v8.4fix: 克隆前解析主机拒绝内网地址，防止借 clone 功能探测内网（SSRF）
        assertNotInternalHost(normalized);
        if (projectId == null || projectId.isBlank()) {
            throw BusinessException.invalidParam("项目标识不能为空");
        }
        Path base = resolveBase();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new BusinessException(50001, "无法创建 Git 克隆目录: " + base, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        Path target = resolveTarget(projectId);
        return cloneToTarget(normalized, target).toString();
    }

    public void deleteClone(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        Path base = resolveBase().toAbsolutePath().normalize();
        Path target = resolveTarget(projectId).toAbsolutePath().normalize();
        if (!target.startsWith(base)) {
            log.warn("Skip git clone cleanup outside managed dir: {}", target);
            return;
        }
        if (!Files.exists(target)) {
            return;
        }
        try (var walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Git clone cleanup failed for {}: {}", projectId, e.toString());
        }
    }

    boolean isValidGitUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.trim();
        if (normalized.length() > MAX_URL_LENGTH || normalized.startsWith("-")) {
            return false;
        }
        return GIT_URL_PATTERN.matcher(normalized).matches();
    }

    // v8.5: SSRF 防护升级——双解析一致性 + 全 A 记录内网判定（SafeDnsResolver），
    // 收敛 v8.4 注释中记录的 DNS rebinding 残留窗口（校验与 git 建连两次解析可能不同；
    // git 进程内部连接仍由 OS 解析一次无法钉死 IP，轻量方案取舍见 SafeDnsResolver 类注释）。
    // ssh/git@ 地址无法本地预解析（通常为企业配置的堡垒机/仓库主机），不拦截仅告警。
    private void assertNotInternalHost(String url) {
        String host = extractHttpHost(url);
        if (host == null || host.isBlank()) {
            if (url.startsWith("ssh://") || url.startsWith("git@")) {
                log.info("SSH Git 地址跳过内网校验（无法预解析）: {}", url);
            }
            return;
        }
        safeDnsResolver.assertStablePublicHost(host, "Git 地址");
    }

    private String extractHttpHost(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("git://")) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    Path cloneToTarget(String url, Path target) {
        if (Files.isDirectory(target) && Files.isDirectory(target.resolve(".git"))) {
            return target;
        }
        deleteQuietly(target);
        Path base = target.getParent();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new BusinessException(50001, "无法创建 Git 克隆目录: " + base, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // v8.1: partial clone——保留全部远端分支引用以支持跨基线 diff，不取文件 blob 控制体积
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--filter=blob:none",
                "--no-single-branch", "--", url, target.toString());
        pb.redirectErrorStream(true);
        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 2000) {
                        output.append(line).append('\n');
                    }
                }
            }
            boolean finished = process.waitFor(cloneTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BusinessException(50002,
                        "Git 克隆超时（超过 " + cloneTimeoutSeconds + " 秒）", HttpStatus.GATEWAY_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                throw new BusinessException(40002,
                        "Git 克隆失败，请检查地址与网络: " + excerpt(output.toString()), HttpStatus.BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new BusinessException(50003,
                    "Git 克隆失败，git 命令不可用或无法执行", HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50002, "Git 克隆被中断", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return target;
    }

    private Path resolveBase() {
        Path base = Paths.get(cloneDir);
        return base.isAbsolute() ? base.normalize() : base.toAbsolutePath().normalize();
    }

    private Path resolveTarget(String projectId) {
        return resolveBase().resolve(projectId);
    }

    private void deleteQuietly(Path target) {
        Path base = resolveBase().toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            log.warn("Skip cleanup outside managed dir: {}", normalized);
            return;
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (var walk = Files.walk(normalized)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Failed to clean partial git clone {}: {}", normalized, e.toString());
        }
    }

    private String excerpt(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500) + "...";
    }
}
