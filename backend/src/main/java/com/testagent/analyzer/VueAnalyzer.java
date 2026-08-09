package com.testagent.analyzer;

import com.testagent.analyzer.result.FrontendResult;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VueAnalyzer {

    private static final Pattern VUE_VERSION_PATTERN =
            Pattern.compile("\"vue\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "path\\s*:\\s*['\"]([^'\"]+)['\"](?:[\\s\\S]{0,300}?name\\s*:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern URL_METHOD_PATTERN = Pattern.compile(
            "url\\s*:\\s*['\"]([^'\"]+)['\"](?:[\\s\\S]{0,200}?method\\s*:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern AXIOS_PATTERN = Pattern.compile(
            "\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    public FrontendResult analyze(String frontendDir) {
        File dir = new File(frontendDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return FrontendResult.skipped();
        }

        Map<String, Object> techStack = detectTechStack(dir);
        List<Map<String, Object>> routes = extractRoutes(dir);
        List<Map<String, Object>> apiCalls = extractApiCalls(dir);
        int fileCount = countSourceFiles(dir);

        return FrontendResult.builder()
                .techStack(techStack)
                .routes(routes)
                .apiCalls(apiCalls)
                .fileCount(fileCount)
                .status("ok")
                .build();
    }

    private Map<String, Object> detectTechStack(File frontendDir) {
        Map<String, Object> tech = new HashMap<>();
        File pkg = new File(frontendDir, "package.json");
        if (!pkg.exists()) {
            return tech;
        }
        try {
            String content = Files.readString(pkg.toPath(), StandardCharsets.UTF_8);
            tech.put("framework", "vue");

            Matcher m = VUE_VERSION_PATTERN.matcher(content);
            if (m.find()) {
                String version = m.group(1);
                tech.put("vueVersion", version);
                if (version.startsWith("3")) {
                    tech.put("vueMajor", 3);
                } else if (version.startsWith("2")) {
                    tech.put("vueMajor", 2);
                }
            }

            if (content.contains("\"element-plus\"")) {
                tech.put("uiFramework", "element-plus");
            } else if (content.contains("\"element-ui\"")) {
                tech.put("uiFramework", "element-ui");
            }
            if (content.contains("\"axios\"")) {
                tech.put("httpClient", "axios");
            }
            if (content.contains("\"vue-router\"")) {
                tech.put("router", "vue-router");
            }
            if (content.contains("\"vuex\"")) {
                tech.put("stateManagement", "vuex");
            } else if (content.contains("\"pinia\"")) {
                tech.put("stateManagement", "pinia");
            }
            if (content.contains("\"vite\"")) {
                tech.put("buildTool", "vite");
            } else if (content.contains("\"webpack\"")) {
                tech.put("buildTool", "webpack");
            }
            if (content.contains("\"typescript\"")) {
                tech.put("language", "typescript");
            } else {
                tech.put("language", "javascript");
            }
        } catch (IOException e) {
            // ignore unreadable package.json
        }
        return tech;
    }

    private List<Map<String, Object>> extractRoutes(File frontendDir) {
        List<Map<String, Object>> routes = new ArrayList<>();
        List<File> routerFiles = findRouterFiles(frontendDir);
        for (File file : routerFiles) {
            String content = readFile(file);
            if (content == null) {
                continue;
            }
            Matcher m = ROUTE_PATTERN.matcher(content);
            while (m.find()) {
                Map<String, Object> route = new HashMap<>();
                route.put("path", m.group(1));
                if (m.group(2) != null) {
                    route.put("name", m.group(2));
                }
                route.put("file", file.getName());
                routes.add(route);
            }
        }
        return routes;
    }

    private List<Map<String, Object>> extractApiCalls(File frontendDir) {
        List<Map<String, Object>> apiCalls = new ArrayList<>();
        File apiDir = new File(frontendDir, "src" + File.separator + "api");
        if (!apiDir.isDirectory()) {
            apiDir = new File(frontendDir, "api");
        }
        if (!apiDir.isDirectory()) {
            return apiCalls;
        }

        List<File> apiFiles = new ArrayList<>();
        collectScriptFiles(apiDir, apiFiles);

        for (File file : apiFiles) {
            String content = readFile(file);
            if (content == null) {
                continue;
            }
            Matcher urlMatcher = URL_METHOD_PATTERN.matcher(content);
            while (urlMatcher.find()) {
                Map<String, Object> call = new HashMap<>();
                call.put("url", urlMatcher.group(1));
                call.put("method", urlMatcher.group(2) != null ? urlMatcher.group(2) : "unknown");
                call.put("file", file.getName());
                apiCalls.add(call);
            }
            Matcher axiosMatcher = AXIOS_PATTERN.matcher(content);
            while (axiosMatcher.find()) {
                Map<String, Object> call = new HashMap<>();
                call.put("url", axiosMatcher.group(2));
                call.put("method", axiosMatcher.group(1));
                call.put("file", file.getName());
                apiCalls.add(call);
            }
        }
        return apiCalls;
    }

    private List<File> findRouterFiles(File frontendDir) {
        List<File> files = new ArrayList<>();
        File srcDir = new File(frontendDir, "src");
        File searchRoot = srcDir.isDirectory() ? srcDir : frontendDir;

        addIfExists(new File(searchRoot, "router" + File.separator + "index.js"), files);
        addIfExists(new File(searchRoot, "router" + File.separator + "index.ts"), files);
        addIfExists(new File(searchRoot, "router.js"), files);
        addIfExists(new File(searchRoot, "router.ts"), files);

        File routerDir = new File(searchRoot, "router");
        if (routerDir.isDirectory()) {
            collectScriptFiles(routerDir, files);
        }
        return files;
    }

    private void collectScriptFiles(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.getName().equals("node_modules")) {
                    continue;
                }
                collectScriptFiles(child, result);
            } else if (child.isFile()) {
                String name = child.getName().toLowerCase();
                if (name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".vue")) {
                    result.add(child);
                }
            }
        }
    }

    private int countSourceFiles(File frontendDir) {
        int[] count = {0};
        countSourceFilesRec(frontendDir, count);
        return count[0];
    }

    private void countSourceFilesRec(File dir, int[] count) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (name.equals("node_modules") || name.equals("dist") || name.equals(".git")) {
                    continue;
                }
                countSourceFilesRec(child, count);
            } else if (child.isFile()) {
                String name = child.getName().toLowerCase();
                if (name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".vue")) {
                    count[0]++;
                }
            }
        }
    }

    private void addIfExists(File file, List<File> files) {
        if (file.exists() && file.isFile() && !files.contains(file)) {
            files.add(file);
        }
    }

    private String readFile(File file) {
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
