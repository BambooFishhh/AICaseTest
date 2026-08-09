package com.testagent.analyzer;

import com.testagent.analyzer.result.ScanResult;
import com.testagent.common.BusinessException;
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

    private static final Pattern VUE_VERSION_PATTERN =
            Pattern.compile("\"vue\"\\s*:\\s*\"([^\"]+)\"");
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
        walk(root, 0, ctx);

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

        if (ctx.frontendDir == null || depth < ctx.frontendDepth) {
            if (new File(dir, "package.json").exists()) {
                ctx.frontendDir = dir.getAbsolutePath();
                ctx.frontendDepth = depth;
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
                if (version.startsWith("3")) {
                    techStack.put("frontendVersion", "vue3");
                } else if (version.startsWith("2")) {
                    techStack.put("frontendVersion", "vue2");
                }
            }

            if (content.contains("\"element-plus\"")) {
                techStack.put("uiFramework", "element-plus");
            } else if (content.contains("\"element-ui\"")) {
                techStack.put("uiFramework", "element-ui");
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
            if (content.contains("spring-boot-starter-data-jpa") || content.contains("hibernate")) {
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

    private static class ScanContext {
        private String frontendDir;
        private String backendDir;
        private int frontendDepth;
        private int backendDepth;
        private int fileCount;
    }
}
