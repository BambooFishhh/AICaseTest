package com.testagent.service;

import com.testagent.dto.JsonHelper;
import com.testagent.entity.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * v1.7: TestCase 列表导出为 CSV。
 * 含 UTF-8 BOM 确保 Excel 正确识别中文；标准 CSV 字段转义。
 */
public class CsvExporter {

    private CsvExporter() {}

    public static byte[] toCsv(List<TestCase> cases) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // UTF-8 BOM: 0xEF 0xBB 0xBF，让 Excel 正确识别中文
        baos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, 0, 3);
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        ps.println("id,title,module,type,priority,preconditions,steps,expectedResults");
        for (TestCase tc : cases) {
            ps.println(String.join(",",
                    csv(tc.getId()),
                    csv(tc.getTitle()),
                    csv(tc.getModule()),
                    csv(tc.getType()),
                    csv(tc.getPriority()),
                    csv(joinList(tc.getPreconditions())),
                    csv(joinList(tc.getSteps())),
                    csv(joinList(tc.getExpectedResults()))));
        }
        ps.flush();
        return baos.toByteArray();
    }

    private static String csv(String field) {
        if (field == null) return "";
        boolean needQuote = field.indexOf(',') >= 0 || field.indexOf('"') >= 0
                || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0;
        if (needQuote) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    private static String joinList(String json) {
        List<String> list = JsonHelper.parseListString(json);
        return String.join(" ; ", list);
    }
}
