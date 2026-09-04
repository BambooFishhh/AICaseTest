package com.testagent.analyzer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.5(点3): collectSourceSnippets 预算分摊单测。
 *
 * 背景：旧实现"按序填满即 break"——同优先级内按字典序，30 个 views 的项目
 * 只有前 ~16 个进 prompt，尾部页面（如 Footprint.vue 排在 Address/Cart 之后）
 * 整体不可见，生成器不知道这些页面存在。新逻辑：默认配额放不下时按文件数
 * 均摊，所有页面都有最小表示；尾部按行边界收尾。
 */
class VueAnalyzerSourceBudgetTest {

    @TempDir
    Path tempDir;

    private final VueAnalyzer analyzer = new VueAnalyzer();

    private void writeView(String name, int templatePad) throws IOException {
        // template 内塞 templatePad 个填充行，每行 ~30 字符
        StringBuilder tpl = new StringBuilder("<template>\n<div>\n");
        for (int i = 0; i < templatePad; i++) {
            tpl.append("<span class=\"row-").append(i).append("\">填充内容用于撑大模板体积</span>\n");
        }
        tpl.append("</div>\n</template>\n");
        tpl.append("<script>\nexport default { name: '").append(name).append("' }\n</script>\n");
        Path path = tempDir.resolve("src/views/" + name + ".vue");
        Files.createDirectories(path.getParent());
        Files.writeString(path, tpl.toString());
    }

    private void setBudget(int total) throws Exception {
        Field f = VueAnalyzer.class.getDeclaredField("vueSourceTotalChars");
        f.setAccessible(true);
        f.setInt(analyzer, total);
    }

    private String collect() throws Exception {
        Method m = VueAnalyzer.class.getDeclaredMethod("collectSourceSnippets", File.class);
        m.setAccessible(true);
        return (String) m.invoke(analyzer, tempDir.toFile());
    }

    private static String header(String name) {
        return "=== " + name + ".vue ===";
    }

    /** 预算充足：所有文件完整进入，行为与旧版一致 */
    @Test
    void smallProjectKeepsFullContent() throws Exception {
        writeView("Collect", 2);
        writeView("Footprint", 2);

        String out = collect();

        assertTrue(out.contains(header("Collect")));
        assertTrue(out.contains(header("Footprint")));
        assertTrue(out.contains("row-1"), "小项目不应截断内容");
    }

    /** 超预算：均摊后每个文件都有表示（旧版尾部文件整体消失） */
    @Test
    void overBudgetFairShareKeepsEveryFileRepresented() throws Exception {
        writeView("Address", 40);
        writeView("Cart", 40);
        writeView("Collect", 40);
        writeView("Footprint", 40);
        writeView("Order", 40);
        setBudget(2000); // 默认配额下 5 个文件约 7000+ 字符，必然超

        String out = collect();

        for (String name : new String[]{"Address", "Cart", "Collect", "Footprint", "Order"}) {
            assertTrue(out.contains(header(name)), "均摊后 " + name + " 不应整体消失");
        }
    }

    /** 均摊模式下总预算受控（允许少量 header 余量误差） */
    @Test
    void overBudgetTotalStaysNearLimit() throws Exception {
        for (String name : new String[]{"A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8"}) {
            writeView(name, 40);
        }
        setBudget(2400);

        String out = collect();

        assertTrue(out.length() <= 2400 + 8 * 40,
                "总长应接近预算上限: " + out.length());
        assertEquals(8, out.split("=== ", -1).length - 1, "8 个文件都应有 header");
    }

    /** 无 .vue 文件返回空串（回归） */
    @Test
    void noVueFilesReturnsEmpty() throws Exception {
        Files.createDirectories(tempDir.resolve("src/views"));
        assertEquals("", collect());
    }

    /** 均摊模式下单文件内容确实被压缩（而非只有 header） */
    @Test
    void fairShareStillCarriesContent() throws Exception {
        writeView("Collect", 40);
        writeView("Footprint", 40);
        setBudget(1200); // 每文件 ~568 字符配额

        String out = collect();

        assertTrue(out.contains("<template>"), "template 头应保留");
        assertTrue(out.indexOf("<template>") < out.lastIndexOf("<template>"),
                "两个文件都应带 template 内容");
        assertFalse(out.contains("row-39"), "大文件内容应被配额压缩");
    }
}
