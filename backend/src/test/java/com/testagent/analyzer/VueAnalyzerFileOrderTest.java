package com.testagent.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v7.13: collectVueFiles 优先级排序单测——A9 的字典序确定性保留，
 * 但纯字典序下 components 排在 views 之前，截断时组件把页面
 * （交互入口、测试价值最高）挤出预算。新排序：views/pages/App.vue
 * > components > 其他，同级路径字典序。
 */
class VueAnalyzerFileOrderTest {

    @TempDir
    Path tempDir;

    private final VueAnalyzer analyzer = new VueAnalyzer();

    private void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<template><div/></template>\n");
    }

    @SuppressWarnings("unchecked")
    private List<File> collect(File dir) throws Exception {
        List<File> result = new ArrayList<>();
        Method m = VueAnalyzer.class.getDeclaredMethod("collectVueFiles", File.class, List.class);
        m.setAccessible(true);
        m.invoke(analyzer, dir, result);
        return result;
    }

    private String name(File f) {
        return f.getName();
    }

    @Test
    void viewsRankBeforeComponentsAndOthers() throws Exception {
        write(tempDir.resolve("src/views/OrderList.vue"));
        write(tempDir.resolve("src/components/UserCard.vue"));
        write(tempDir.resolve("src/layouts/BasicLayout.vue"));

        List<File> files = collect(tempDir.toFile());

        assertEquals(3, files.size());
        assertEquals("OrderList.vue", name(files.get(0)), "views 页面应排第一");
        assertEquals("UserCard.vue", name(files.get(1)), "components 应排第二");
        assertEquals("BasicLayout.vue", name(files.get(2)), "其余排最后");
    }

    @Test
    void pagesAndAppVueAlsoRankFirst() throws Exception {
        write(tempDir.resolve("src/pages/Login.vue"));
        write(tempDir.resolve("src/App.vue"));
        write(tempDir.resolve("src/components/Header.vue"));

        List<File> files = collect(tempDir.toFile());

        assertEquals("App.vue", name(files.get(0)), "App.vue 路由入口优先");
        assertEquals("Login.vue", name(files.get(1)), "pages 与 views 同级优先");
        assertEquals("Header.vue", name(files.get(2)));
    }

    @Test
    void samePrioritySortedByPathDeterministically() throws Exception {
        // 同级按路径字典序 + 两次收集顺序一致（A9 确定性保持）
        write(tempDir.resolve("src/views/Zebra.vue"));
        write(tempDir.resolve("src/views/Apple.vue"));
        write(tempDir.resolve("src/views/Mango.vue"));

        List<File> first = collect(tempDir.toFile());
        List<File> second = collect(tempDir.toFile());

        assertEquals(List.of("Apple.vue", "Mango.vue", "Zebra.vue"),
                first.stream().map(this::name).toList(), "同级按字典序");
        assertEquals(first.stream().map(File::getAbsolutePath).toList(),
                second.stream().map(File::getAbsolutePath).toList(), "两次收集顺序一致");
    }

    @Test
    void nodeModulesStillSkipped() throws Exception {
        write(tempDir.resolve("src/views/Home.vue"));
        write(tempDir.resolve("node_modules/pkg/dist/Some.vue"));

        List<File> files = collect(tempDir.toFile());

        assertEquals(1, files.size());
        assertTrue(files.get(0).getName().endsWith("Home.vue"));
    }
}
