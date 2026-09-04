package com.testagent.analyzer;

import com.testagent.analyzer.result.ScanResult;
import com.testagent.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProjectScanner {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanner.class);

    private static final Pattern VUE_VERSION_PATTERN =
            Pattern.compile("\"vue\"\\s*:\\s*\"([^\"]+)\"");
    // v13.1: 前端框架依赖信号——判定"某个 package.json 是否真属于前端工程"。
    // 仅凭 package.json 存在就认定前端根是错的：monorepo / workspace 根目录的 package.json
    // 通常只有 workspaces/scripts 配置、不含框架依赖，会把真正的 frontend/ 子目录挤掉，
    // 导致后续 routes 提取落空（src/router 找不到）且无任何告警。
    private static final Pattern FRONTEND_FRAMEWORK_PATTERN =
            Pattern.compile("\"(vue|react|angular|svelte)\"\\s*:\\s*\"[^\"]+\"");
    private static final Pattern SPRING_BOOT_PARENT_VERSION_PATTERN =
            Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern SPRING_BOOT_VERSION_PROPERTY_PATTERN =
            Pattern.compile("<spring-boot\\.version>([^<]+)</spring-boot\\.version>");

    public ScanResult scan(String sourcePath) {
        File root = new File(sourcePath);
        if (!root.exists() || !root.isDirectory()) {
            throw BusinessException.pathNotFound(sourcePath);
        }

        ScanContext ctx = new ScanContext();
        ctx.frontendDepth = Integer.MAX_VALUE;
        ctx.backendDepth = Integer.MAX_VALUE;
        ctx.weakFrontendDepth = Integer.MAX_VALUE;
        walk(root, 0, ctx);

        // v13.1: 无任何含框架依赖的前端根时回退最浅弱候选——
        // 保守兼容旧行为（不至于从"能扫"退化成"扫不到"），但显式告警，避免静默失败
        if (ctx.frontendDir == null && ctx.weakFrontendDir != null) {
            ctx.frontendDir = ctx.weakFrontendDir;
            ctx.frontendDepth = ctx.weakFrontendDepth;
            log.warn("[Scan] 未找到含前端框架依赖(vue/react/angular/svelte)的 package.json，"
                            + "回退到最浅候选: {}（若为 monorepo/workspace 根，routes 与前端技术栈可能提取不全）",
                    ctx.weakFrontendDir);
        }

        Map<String, Object> techStack = new HashMap<>();
        if (ctx.frontendDir != null) {
            detectFrontendTech(new File(ctx.frontendDir), techStack);
        }
        if (ctx.backendDir != null) {
            detectBackendTech(new File(ctx.backendDir), techStack);
        }
        if (ctx.frontendDir == null && ctx.backendDir == null) {
            techStack.put("type", "unknown");
        }

        return ScanResult.builder()
                .frontendDir(ctx.frontendDir)
                .backendDir(ctx.backendDir)
                .techStack(techStack)
                .fileCount(ctx.fileCount)
                .build();
    }

    private void walk(File dir, int depth, ScanContext ctx) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }

        // v13.1: 前端根分两级判定——含框架依赖的才算"合格前端根"，
        // 仅有 package.json（workspace 根/工具配置根）的降级为弱候选，
        // 让更深层真正的前端目录有机会胜出；全部无合格时回退最浅弱候选（见 scan）。
        File packageJson = new File(dir, "package.json");
        if (packageJson.exists()) {
            if (hasFrontendFramework(packageJson)) {
                if (ctx.frontendDir == null || depth < ctx.frontendDepth) {
                    ctx.frontendDir = dir.getAbsolutePath();
                    ctx.frontendDepth = depth;
                }
            } else if (ctx.weakFrontendDir == null || depth < ctx.weakFrontendDepth) {
                ctx.weakFrontendDir = dir.getAbsolutePath();
                ctx.weakFrontendDepth = depth;
            }
        }
        if (ctx.backendDir == null || depth < ctx.backendDepth) {
            if (new File(dir, "pom.xml").exists()
                    || new File(dir, "src" + File.separator + "main" + File.separator + "java").exists()) {
                ctx.backendDir = dir.getAbsolutePath();
                ctx.backendDepth = depth;
            }
        }

        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (name.equals("node_modules") || name.equals(".git")
                        || name.equals("target") || name.equals("dist")
                        || name.equals("build") || name.equals(".idea")) {
                    continue;
                }
                walk(child, depth + 1, ctx);
            } else if (child.isFile()) {
                ctx.fileCount++;
            }
        }
    }

    private void detectFrontendTech(File frontendDir, Map<String, Object> techStack) {
        File pkg = new File(frontendDir, "package.json");
        if (!pkg.exists()) {
            return;
        }
        try {
            String content = Files.readString(pkg.toPath(), StandardCharsets.UTF_8);
            techStack.put("frontend", "vue");

            Matcher m = VUE_VERSION_PATTERN.matcher(content);
            if (m.find()) {
                String version = m.group(1);
                techStack.put("vueVersion", version);
                String majorPrefix = stripVersionPrefix(version);
                if (majorPrefix.startsWith("3")) {
                    techStack.put("frontendVersion", "vue3");
                } else if (majorPrefix.startsWith("2")) {
                    techStack.put("frontendVersion", "vue2");
                }
            }

            if (content.contains("\"element-plus\"")) {
                techStack.put("uiFramework", "element-plus");
            } else if (content.contains("\"element-ui\"")) {
                techStack.put("uiFramework", "element-ui");
            } else if (content.contains("\"vant\"") || content.contains("\"@vant/")) {
                // v13.5(Bug B): vant 移动端组件库此前不识别——litemall-mall(vant) 落库无 uiFramework
                techStack.put("uiFramework", "vant");
            }
            if (content.contains("\"axios\"")) {
                techStack.put("httpClient", "axios");
            }
            if (content.contains("\"vue-router\"")) {
                techStack.put("router", "vue-router");
            }
            if (content.contains("\"vuex\"")) {
                techStack.put("stateManagement", "vuex");
            } else if (content.contains("\"pinia\"")) {
                techStack.put("stateManagement", "pinia");
            }
            if (content.contains("\"vite\"")) {
                techStack.put("buildTool", "vite");
            } else if (content.contains("\"webpack\"")) {
                techStack.put("buildTool", "webpack");
            }
            if (content.contains("\"typescript\"") || content.contains("\"ts\"")) {
                techStack.put("language", "typescript");
            } else {
                techStack.put("language", "javascript");
            }
        } catch (IOException e) {
            // ignore unreadable package.json
        }
    }

    private void detectBackendTech(File backendDir, Map<String, Object> techStack) {
        File pom = new File(backendDir, "pom.xml");
        if (!pom.exists()) {
            return;
        }
        try {
            String content = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            techStack.put("backend", "spring-boot");
            techStack.put("backendLanguage", "java");

            if (content.contains("spring-boot-starter-parent") || content.contains("spring-boot-starter")) {
                techStack.put("backendFramework", "spring-boot");
                Matcher m = SPRING_BOOT_PARENT_VERSION_PATTERN.matcher(content);
                if (m.find()) {
                    techStack.put("springBootVersion", m.group(1));
                } else {
                    Matcher m2 = SPRING_BOOT_VERSION_PROPERTY_PATTERN.matcher(content);
                    if (m2.find()) {
                        techStack.put("springBootVersion", m2.group(1));
                    }
                }
            }
            if (content.contains("mybatis")) {
                techStack.put("orm", "mybatis");
            }
            if (content.contains("shiro")) {
                techStack.put("security", "shiro");
            } else if (content.contains("spring-boot-starter-security")) {
                techStack.put("security", "spring-security");
            }
            // v13.1: 原 contains("hibernate") 过宽——hibernate-validator 是 Bean Validation
            // 实现（参数校验，与 ORM 无关），会把纯 mybatis 项目误标为 jpa 持久层
            // （litemall 实测：仅引入 hibernate-validator，却被判 persistence=jpa）。
            // 收紧为 data-jpa starter 或 hibernate-core（真正的 ORM 内核）。
            if (content.contains("spring-boot-starter-data-jpa") || content.contains("hibernate-core")) {
                techStack.put("persistence", "jpa");
            }
            if (content.contains("redis") || content.contains("spring-boot-starter-data-redis")) {
                techStack.put("cache", "redis");
            }
            if (content.contains("swagger") || content.contains("springdoc")) {
                techStack.put("apiDocs", "swagger");
            }
        } catch (IOException e) {
            // ignore unreadable pom.xml
        }
    }

    /**
     * v13.1: package.json 是否含前端框架依赖信号（vue/react/angular/svelte）。
     * 读取失败时返回 true——不因 IO 问题把合格前端根误降级（保守沿用最浅优先语义）。
     */
    private boolean hasFrontendFramework(File packageJson) {
        try {
            String content = Files.readString(packageJson.toPath(), StandardCharsets.UTF_8);
            return FRONTEND_FRAMEWORK_PATTERN.matcher(content).find();
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * v13.1: npm 版本号常带 "^" / "~" / ">= " 等语义化前缀（如 "^3.5.13"），
     * 直接 startsWith("3") 恒为 false，会导致 vue2/vue3 判定永久失效、frontendVersion 静默缺失。
     */
    private static String stripVersionPrefix(String version) {
        if (version == null) {
            return "";
        }
        return version.replaceAll("^[\\^~><=\\s]+", "");
    }

    private static class ScanContext {
        private String frontendDir;
        private String backendDir;
        /** v13.1: 有 package.json 但无框架依赖的弱候选（workspace 根/工具配置根） */
        private String weakFrontendDir;
        private int frontendDepth;
        private int backendDepth;
        private int weakFrontendDepth;
        private int fileCount;
    }
}
