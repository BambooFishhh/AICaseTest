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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v13.5(Bug B): extractForms vant 移动端表单识别单测。
 *
 * 背景：extractForms 原先只认 el-form-item/el-input 等 Element 系控件，
 * vant(van-*) 移动端表单整类扫描空白——van-field 自带 name/label/:rules，
 * 无 el-form-item 包装层。此处验证：
 *   1. van-field 行内 :rules 与 script rules 块两条校验来源均生效；
 *   2. name 缺失回退 label；name/label 均缺失跳过；
 *   3. el 表单解析行为不变（回归）。
 */
class VueAnalyzerVantFormsTest {

    @TempDir
    Path tempDir;

    private final VueAnalyzer analyzer = new VueAnalyzer();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extract() throws Exception {
        Method m = VueAnalyzer.class.getDeclaredMethod("extractForms", File.class, List.class);
        m.setAccessible(true);
        return (List<Map<String, Object>>) m.invoke(analyzer, tempDir.toFile(), new ArrayList<>());
    }

    private void writeVue(String name, String content) throws IOException {
        Path path = tempDir.resolve("src/views/" + name + ".vue");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    /** 行内 :rules 的 van-field（vant 4 主流写法） */
    @Test
    void vanFieldWithInlineRulesIsExtracted() throws Exception {
        writeVue("Login", "<template>\n<van-form>\n"
                + "  <van-field v-model=\"username\" name=\"username\" label=\"用户名\" placeholder=\"请输入\"\n"
                + "    :rules=\"[{ required: true, message: '请输入用户名' }]\" />\n"
                + "  <van-field v-model=\"password\" name=\"password\" label=\"密码\" type=\"password\" />\n"
                + "</van-form>\n</template>\n<script>\nexport default { data() { return { username: '', password: '' } } }\n</script>\n");

        List<Map<String, Object>> forms = extract();

        assertEquals(1, forms.size(), "vant 表单应被识别");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) forms.get(0).get("fields");
        assertEquals(2, fields.size(), "两个 van-field 都应提取");
        assertEquals("username", fields.get(0).get("name"));
        assertEquals("用户名", fields.get(0).get("label"));
        assertEquals("van-field", fields.get(0).get("type"));
        assertEquals(Boolean.TRUE, fields.get(0).get("required"), "行内 required 应识别");
        assertTrue(((List<String>) fields.get(0).get("rules")).contains("required"));
        assertEquals(Boolean.FALSE, fields.get(1).get("required"), "无 :rules 的字段不应误标 required");
    }

    /** script rules 块（vant + el 混用同一解析路径）+ name 缺失回退 label */
    @Test
    void scriptRulesAndLabelFallback() throws Exception {
        writeVue("Address", "<template>\n<van-form>\n"
                + "  <van-field v-model=\"name\" name=\"name\" label=\"收货人\" />\n"
                + "  <van-field v-model=\"tel\" label=\"电话\" />\n"
                + "</van-form>\n</template>\n"
                + "<script>\nexport default {\n  data() { return { name: '', tel: '' } },\n"
                + "  rules: { name: [{ required: true, message: '请输入收货人' }] }\n}\n</script>\n");

        List<Map<String, Object>> forms = extract();

        assertEquals(1, forms.size());
        List<Map<String, Object>> fields = (List<Map<String, Object>>) forms.get(0).get("fields");
        assertEquals(2, fields.size());
        assertEquals(Boolean.TRUE, fields.get(0).get("required"), "script rules 块应命中 name 字段");
        assertEquals("电话", fields.get(1).get("name"), "name 缺失应回退 label");
    }

    /** name/label 均缺失的 van-field 跳过，不产生空字段 */
    @Test
    void fieldWithoutNameAndLabelSkipped() throws Exception {
        writeVue("Search", "<template>\n<div>\n"
                + "  <van-field v-model=\"kw\" placeholder=\"搜索\" />\n"
                + "</div>\n</template>\n<script>\nexport default { data() { return { kw: '' } } }\n</script>\n");

        assertTrue(extract().isEmpty(), "无 name/label 的 van-field 不应产生表单");
    }

    /** 回归：el-form-item 解析行为不变 */
    @Test
    void elementFormStillExtracted() throws Exception {
        writeVue("Admin", "<template>\n<el-form>\n"
                + "  <el-form-item prop=\"title\" label=\"标题\"><el-input v-model=\"title\" /></el-form-item>\n"
                + "</el-form>\n</template>\n"
                + "<script>\nexport default { data() { return { title: '' } }, rules: { title: [{ required: true }] } }\n</script>\n");

        List<Map<String, Object>> forms = extract();

        assertEquals(1, forms.size());
        List<Map<String, Object>> fields = (List<Map<String, Object>>) forms.get(0).get("fields");
        assertEquals(1, fields.size());
        assertEquals("title", fields.get(0).get("name"));
        assertEquals("el-input", fields.get(0).get("type"));
        assertEquals(Boolean.TRUE, fields.get(0).get("required"));
    }
}
