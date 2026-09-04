package com.testagent.analyzer;

import com.testagent.analyzer.result.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.1: ProjectScanner 前端根定位单测。
 *
 * 背景：此前仅凭"目录存在 package.json"+"最浅优先"认定前端根。monorepo/workspace
 * 根目录的 package.json 通常只有 workspaces/scripts 配置、不含框架依赖，会把真正的
 * frontend/ 子目录挤掉——后续 routes 提取（找 src/router/**）落空且无任何告警，
 * 属于静默失败。
 *
 * 修复策略：含前端框架依赖(vue/react/angular/svelte)的才算合格前端根；仅有 package.json 的
 * 降级为弱候选；全部无合格时回退最浅弱候选（保守兼容，不至于从"能扫"退化成"扫不到"）。
 */
class ProjectScannerTest {

    @TempDir
    Path tempDir;

    private final ProjectScanner scanner = new ProjectScanner();

    private void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /** workspace 根 package.json（无框架依赖，只有 workspaces 配置） */
    private static final String WORKSPACE_PKG =
            "{\"name\":\"my-monorepo\",\"private\":true,\"workspaces\":[\"frontend\",\"backend\"]}";

    /** 真正的前端 package.json（含 vue 依赖） */
    private static final String VUE_PKG =
            "{\"name\":\"app\",\"dependencies\":{\"vue\":\"^3.5.13\",\"vue-router\":\"^4.0.0\"}}";

    /**
     * v13.5(Bug B): vant 移动端组件库应识别为 uiFramework（litemall-mall 实测缺失）。
     */
    @Test
    void vantUiFrameworkDetected() throws IOException {
        Path root = tempDir.resolve("mall");
        write(root.resolve("package.json"),
                "{\"name\":\"mall\",\"dependencies\":{\"vue\":\"^3.5.13\",\"vant\":\"^4.9.0\"}}");

        ScanResult result = scanner.scan(root.toString());

        assertEquals("vant", result.getTechStack().get("uiFramework"), "vant 应被识别为 uiFramework");
    }

    /** scoped 包 @vant/* 也应命中；el-* 项目不受影响（回归） */
    @Test
    void scopedVantAndElementRegression() throws IOException {
        Path vantRoot = tempDir.resolve("mall2");
        write(vantRoot.resolve("package.json"),
                "{\"name\":\"mall2\",\"dependencies\":{\"vue\":\"^3.2.0\",\"@vant/touch-emulator\":\"^1.4.0\"}}");
        assertEquals("vant", scanner.scan(vantRoot.toString()).getTechStack().get("uiFramework"),
                "@vant/ scoped 包也应命中");

        Path elRoot = tempDir.resolve("admin");
        write(elRoot.resolve("package.json"),
                "{\"name\":\"admin\",\"dependencies\":{\"vue\":\"^3.2.0\",\"element-plus\":\"^2.0.0\",\"vant\":\"^4.0.0\"}}");
        assertEquals("element-plus", scanner.scan(elRoot.toString()).getTechStack().get("uiFramework"),
                "element-plus 优先级高于 vant（回归）");
    }

    /**
     * 核心用例：workspace 根有 package.json 但无框架依赖，真正前端在 frontend/ 下。
     * 修复前：frontendDir = 根（错误，routes 全空）；修复后：frontendDir = frontend/。
     */
    @Test
    void workspaceRootWithoutFrameworkShouldNotWinOverRealFrontend() throws IOException {
        Path root = tempDir.resolve("monorepo");
        write(root.resolve("package.json"), WORKSPACE_PKG);
        write(root.resolve("frontend/package.json"), VUE_PKG);
        write(root.resolve("frontend/src/router/index.js"), "export default []");
        write(root.resolve("backend/pom.xml"), "<project><artifactId>backend</artifactId></project>");

        ScanResult result = scanner.scan(root.toString());

        assertEquals(root.resolve("frontend").toFile().getAbsolutePath(), result.getFrontendDir(),
                "workspace 根的 package.json 无框架依赖，前端根应落到含 vue 依赖的 frontend/");
        assertEquals(root.resolve("backend").toFile().getAbsolutePath(), result.getBackendDir());
    }

    /**
     * 回归用例（当前实测项目 litemall-mall 即此形态）：前端就在根，根 package.json 含 vue。
     * 修复不能让这类项目退化。
     */
    @Test
    void frontendAtRootStillDetected() throws IOException {
        Path root = tempDir.resolve("vite-app");
        write(root.resolve("package.json"), VUE_PKG);
        write(root.resolve("src/router/index.js"), "export default []");
        write(root.resolve("pom.xml"), "<project><artifactId>parent</artifactId></project>");

        ScanResult result = scanner.scan(root.toString());

        assertEquals(root.toFile().getAbsolutePath(), result.getFrontendDir(),
                "前端就在根时仍应定位到根（litemall-mall 形态，不能退化）");
    }

    /**
     * 兜底用例：只有 workspace 根 package.json、没有任何含框架的前端目录。
     * 应回退到最浅弱候选（旧行为），保证不比修复前差。
     */
    @Test
    void fallsBackToShallowestCandidateWhenNoFrameworkFound() throws IOException {
        Path root = tempDir.resolve("scripts-only");
        write(root.resolve("package.json"), WORKSPACE_PKG);
        write(root.resolve("backend/pom.xml"), "<project><artifactId>backend</artifactId></project>");

        ScanResult result = scanner.scan(root.toString());

        assertEquals(root.toFile().getAbsolutePath(), result.getFrontendDir(),
                "无合格前端根时应回退最浅候选，保持旧行为");
    }

    /** 纯后端项目：不应误报前端根 */
    @Test
    void pureBackendProjectHasNoFrontendDir() throws IOException {
        Path root = tempDir.resolve("backend-only");
        write(root.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        write(root.resolve("src/main/java/Demo.java"), "class Demo {}");

        ScanResult result = scanner.scan(root.toString());

        assertNull(result.getFrontendDir());
        assertEquals(root.toFile().getAbsolutePath(), result.getBackendDir());
    }

    /**
     * v13.1 Bug A：npm 版本号带 "^" 前缀时 vue2/vue3 判定失效。
     * "^3.5.13".startsWith("3") == false，导致 frontendVersion 静默缺失。
     */
    @Test
    void vueVersionWithCaretPrefixShouldDetectVue3() throws IOException {
        Path root = tempDir.resolve("vue3-app");
        write(root.resolve("package.json"), VUE_PKG);

        ScanResult result = scanner.scan(root.toString());

        assertEquals("^3.5.13", result.getTechStack().get("vueVersion"));
        assertEquals("vue3", result.getTechStack().get("frontendVersion"),
                "\"^3.5.13\" 剥离语义化前缀后应识别为 vue3");
    }

    /** 同理覆盖 vue2 与 ~ 前缀 */
    @Test
    void vueVersionWithTildePrefixShouldDetectVue2() throws IOException {
        Path root = tempDir.resolve("vue2-app");
        write(root.resolve("package.json"),
                "{\"name\":\"app\",\"dependencies\":{\"vue\":\"~2.6.14\"}}");

        ScanResult result = scanner.scan(root.toString());

        assertEquals("vue2", result.getTechStack().get("frontendVersion"),
                "\"~2.6.14\" 剥离语义化前缀后应识别为 vue2");
    }

    /** 前端根定位正确后，路由文件应能被后续分析找到（src/router 在 frontend 下而非根） */
    @Test
    void routerLivesUnderResolvedFrontendDir() throws IOException {
        Path root = tempDir.resolve("monorepo2");
        write(root.resolve("package.json"), WORKSPACE_PKG);
        write(root.resolve("frontend/package.json"), VUE_PKG);
        write(root.resolve("frontend/src/router/index.js"), "export default []");

        ScanResult result = scanner.scan(root.toString());

        Path router = Path.of(result.getFrontendDir()).resolve("src/router/index.js");
        assertTrue(Files.exists(router),
                "前端根定位正确后，src/router 应存在于其下（否则 VueAnalyzer 提取不到 routes）");
    }

    /**
     * v13.1 Bug C：hibernate-validator 是 Bean Validation 实现（参数校验，与 ORM 无关），
     * 原 contains("hibernate") 会把纯 mybatis 项目误标为 persistence=jpa（litemall 实测命中）。
     */
    @Test
    void hibernateValidatorShouldNotBeTreatedAsJpa() throws IOException {
        Path root = tempDir.resolve("mybatis-app");
        write(root.resolve("pom.xml"), "<project><dependencies>"
                + "<dependency><groupId>org.mybatis.spring.boot</groupId>"
                + "<artifactId>mybatis-spring-boot-starter</artifactId></dependency>"
                + "<dependency><groupId>org.hibernate.validator</groupId>"
                + "<artifactId>hibernate-validator</artifactId></dependency>"
                + "</dependencies></project>");

        ScanResult result = scanner.scan(root.toString());

        assertEquals("mybatis", result.getTechStack().get("orm"));
        assertNull(result.getTechStack().get("persistence"),
                "仅引入 hibernate-validator（参数校验）不应被判为 jpa 持久层");
    }

    /** 回归：真正的 JPA 项目仍应识别（收紧匹配不能误杀） */
    @Test
    void realJpaProjectStillDetected() throws IOException {
        Path root = tempDir.resolve("jpa-app");
        write(root.resolve("pom.xml"), "<project><dependencies>"
                + "<dependency><groupId>org.springframework.boot</groupId>"
                + "<artifactId>spring-boot-starter-data-jpa</artifactId></dependency>"
                + "</dependencies></project>");

        ScanResult result = scanner.scan(root.toString());

        assertEquals("jpa", result.getTechStack().get("persistence"),
                "真正的 JPA 项目仍应识别为 jpa");
    }
}
