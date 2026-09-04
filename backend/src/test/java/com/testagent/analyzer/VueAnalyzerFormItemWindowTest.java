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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v13.7(点1第7条): form-item 控件类型查找窗口单测。
 *
 * 旧固定 600 字符窗口：长 label/长属性下控件落窗外误记 unknown。
 * 新逻辑：延伸至下一个 el-form-item 或 1500 字符——既找到更远的控件，
 * 又不会吃进下一字段的控件（错标类型）。
 */
class VueAnalyzerFormItemWindowTest {

    @TempDir
    Path tempDir;

    private final VueAnalyzer analyzer = new VueAnalyzer();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extract() throws Exception {
        Method m = VueAnalyzer.class.getDeclaredMethod("extractForms", File.class, List.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(analyzer, tempDir.toFile(), new ArrayList<>());
    }

    private void writeVue(String content) throws IOException {
        Files.createDirectories(tempDir.resolve("src/views"));
        Files.writeString(tempDir.resolve("src/views/Demo.vue"), content);
    }

    /** 控件在 600 字符之外（长注释撑开距离）：旧实现记 unknown，新实现应找到 el-input */
    @Test
    void controlBeyond600CharsStillFound() throws Exception {
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            filler.append("  <!-- 冗长的模板块注释用于撑开控件与 form-item 的距离 ").append(i).append(" -->\n");
        }
        writeVue("<template>\n<el-form>\n"
                + "  <el-form-item prop=\"title\" label=\"标题\">\n"
                + filler
                + "    <el-input v-model=\"title\" />\n"
                + "  </el-form-item>\n"
                + "</el-form>\n</template>\n");

        List<Map<String, Object>> forms = extract();

        assertEquals(1, forms.size());
        List<Map<String, Object>> fields = (List<Map<String, Object>>) forms.get(0).get("fields");
        assertEquals(1, fields.size());
        assertEquals("el-input", fields.get(0).get("type"), "600 字符外的控件应被找到（旧实现 unknown）");
    }

    /** 窗口不跨字段：无控件的第一字段不得吃进第二字段的 el-input */
    @Test
    void windowDoesNotStealNextFieldControl() throws Exception {
        writeVue("<template>\n<el-form>\n"
                + "  <el-form-item prop=\"textOnly\" label=\"纯文本\">说明文字，无控件</el-form-item>\n"
                + "  <el-form-item prop=\"real\" label=\"真实字段\"><el-input v-model=\"real\" /></el-form-item>\n"
                + "</el-form>\n</template>\n");

        List<Map<String, Object>> forms = extract();

        assertEquals(1, forms.size());
        List<Map<String, Object>> fields = (List<Map<String, Object>>) forms.get(0).get("fields");
        assertEquals(2, fields.size());
        assertEquals("unknown", fields.get(0).get("type"),
                "无控件字段不得吃进下一字段的 el-input");
        assertEquals("el-input", fields.get(1).get("type"));
    }
}
