package com.testagent.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VueAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VueAnalyzer.class);

    @Autowired
    private LlmService llmService;

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

        // v1.11: 深度提取表单、组件状态、DOM 选择器、页面跳转
        List<Map<String, Object>> forms = extractForms(dir);
        List<Map<String, Object>> componentStates = extractComponentStates(dir);
        List<Map<String, Object>> domSelectors = extractDomSelectors(dir);
        List<Map<String, Object>> pageFlows = extractPageFlows(dir);

        // v1.12: LLM 补充正则遗漏的内容
        if (llmService.isConfigured()) {
            try {
                enhanceWithLlm(forms, componentStates, domSelectors, pageFlows, dir);
            } catch (Exception e) {
                log.warn("LLM enhancement failed, using regex-only results: {}", e.getMessage());
            }
        }

        return FrontendResult.builder()
                .techStack(techStack)
                .routes(routes)
                .apiCalls(apiCalls)
                .forms(forms)
                .componentStates(componentStates)
                .domSelectors(domSelectors)
                .pageFlows(pageFlows)
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
                String name = child.getName();
                if (name.equals("node_modules") || name.equals("dist") || name.equals(".git")) {
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

    /**
     * 递归收集所有 .vue 文件（跳过 node_modules）
     */
    private void collectVueFiles(File dir, List<File> result) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.getName().equals("node_modules")) {
                    continue;
                }
                collectVueFiles(child, result);
            } else if (child.isFile()) {
                String name = child.getName().toLowerCase();
                if (name.endsWith(".vue")) {
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

    // ==================== v1.11 深度提取方法 ====================

    /**
     * 提取表单字段与校验规则。
     * 扫描所有 .vue 文件中的 el-form-item / el-input 等控件以及 script 中的 rules 配置。
     */
    private List<Map<String, Object>> extractForms(File dir) {
        List<Map<String, Object>> forms = new ArrayList<>();
        try {
            List<File> vueFiles = new ArrayList<>();
            collectVueFiles(dir, vueFiles);

            Pattern formItemPattern = Pattern.compile("<el-form-item\\b([^>]*)>");
            Pattern propPattern = Pattern.compile("prop\\s*=\\s*\"([^\"]+)\"");
            Pattern labelPattern = Pattern.compile("label\\s*=\\s*\"([^\"]+)\"");
            // 常见 Element Plus 表单控件
            Pattern inputTypePattern = Pattern.compile(
                    "<(el-input|el-select|el-date-picker|el-input-number|el-radio-group|el-checkbox-group|"
                            + "el-cascader|el-switch|el-time-picker|el-upload|el-autocomplete)\\b");
            Pattern rulesStartPattern = Pattern.compile("rules\\s*[:=]\\s*\\{");

            for (File file : vueFiles) {
                String content = readFile(file);
                if (content == null) {
                    continue;
                }
                String component = stripExtension(file.getName());

                // 解析 rules 对象块（处理嵌套大括号）
                String rulesBlock = null;
                Matcher rs = rulesStartPattern.matcher(content);
                if (rs.find()) {
                    int openIdx = rs.end() - 1; // 指向 '{'
                    rulesBlock = extractBalanced(content, openIdx, '{', '}');
                }

                List<Map<String, Object>> fields = new ArrayList<>();
                Matcher fi = formItemPattern.matcher(content);
                while (fi.find()) {
                    String tagAttrs = fi.group(1);

                    String name = null;
                    Matcher pm = propPattern.matcher(tagAttrs);
                    if (pm.find()) {
                        name = pm.group(1);
                    }
                    // 没有 prop 的 form-item 无法关联校验，跳过
                    if (name == null) {
                        continue;
                    }

                    String label = null;
                    Matcher lm = labelPattern.matcher(tagAttrs);
                    if (lm.find()) {
                        label = lm.group(1);
                    }

                    // 在 form-item 开始标签之后查找控件类型
                    String type = "unknown";
                    int afterEnd = Math.min(content.length(), fi.end() + 600);
                    String after = content.substring(fi.end(), afterEnd);
                    Matcher tm = inputTypePattern.matcher(after);
                    if (tm.find()) {
                        type = tm.group(1);
                    }

                    // 解析该字段对应的校验规则
                    List<String> rules = new ArrayList<>();
                    boolean required = false;
                    if (rulesBlock != null) {
                        Pattern fieldRulePattern = Pattern.compile(Pattern.quote(name) + "\\s*:\\s*\\[");
                        Matcher frm = fieldRulePattern.matcher(rulesBlock);
                        if (frm.find()) {
                            int arrIdx = frm.end() - 1; // 指向 '['
                            String arrBlock = extractBalanced(rulesBlock, arrIdx, '[', ']');
                            if (arrBlock != null) {
                                if (arrBlock.matches("(?s).*required\\s*:\\s*true.*")) {
                                    rules.add("required");
                                    required = true;
                                }
                                Matcher minM = Pattern.compile("min\\s*:\\s*(\\d+)").matcher(arrBlock);
                                if (minM.find()) {
                                    rules.add("min:" + minM.group(1));
                                }
                                Matcher maxM = Pattern.compile("max\\s*:\\s*(\\d+)").matcher(arrBlock);
                                if (maxM.find()) {
                                    rules.add("max:" + maxM.group(1));
                                }
                                Matcher patM = Pattern.compile("pattern\\s*:\\s*([^,}\\]]+)").matcher(arrBlock);
                                if (patM.find()) {
                                    rules.add("pattern:" + patM.group(1).trim());
                                }
                            }
                        }
                    }

                    Map<String, Object> field = new HashMap<>();
                    field.put("name", name);
                    field.put("type", type);
                    field.put("label", label);
                    field.put("required", required);
                    field.put("rules", rules);
                    fields.add(field);
                }

                if (fields.isEmpty()) {
                    continue;
                }

                Map<String, Object> form = new HashMap<>();
                form.put("component", component);
                form.put("fields", fields);
                form.put("file", file.getName());
                forms.add(form);
            }
        } catch (Exception e) {
            // 失败时返回空列表，不阻断分析
        }
        return forms;
    }

    /**
     * 提取组件交互状态（弹窗/抽屉/分步/标签页）。
     */
    private List<Map<String, Object>> extractComponentStates(File dir) {
        List<Map<String, Object>> states = new ArrayList<>();
        try {
            List<File> vueFiles = new ArrayList<>();
            collectVueFiles(dir, vueFiles);

            Pattern[] tagPatterns = {
                    Pattern.compile("<el-dialog\\b([^>]*)>"),
                    Pattern.compile("<el-drawer\\b([^>]*)>"),
                    Pattern.compile("<el-steps\\b([^>]*)>"),
                    Pattern.compile("<el-tabs\\b([^>]*)>")
            };
            String[] types = {"dialog", "drawer", "steps", "tabs"};
            Pattern vModelPattern = Pattern.compile("v-model\\s*=\\s*\"([^\"]+)\"");
            // :visible="xxx" 或 v-if="xxxVisible"
            Pattern visiblePattern = Pattern.compile("(?::visible|v-if)\\s*=\\s*\"([^\"]+)\"");
            Pattern clickPattern = Pattern.compile("@click=\"([^\"]+)\"");
            Pattern assignTruePattern = Pattern.compile("(\\w+)\\s*=\\s*true");
            Pattern wordPattern = Pattern.compile("(\\w+)");

            for (File file : vueFiles) {
                String content = readFile(file);
                if (content == null) {
                    continue;
                }
                String component = stripExtension(file.getName());

                // 先收集所有 @click="xxx = true" 形式的触发器，按变量名索引
                Map<String, String> triggers = new HashMap<>();
                Matcher cm = clickPattern.matcher(content);
                while (cm.find()) {
                    String expr = cm.group(1).trim();
                    Matcher am = assignTruePattern.matcher(expr);
                    if (am.find()) {
                        triggers.putIfAbsent(am.group(1), expr);
                    }
                }

                for (int t = 0; t < tagPatterns.length; t++) {
                    Matcher m = tagPatterns[t].matcher(content);
                    while (m.find()) {
                        String attrs = m.group(1);

                        String stateVar = null;
                        Matcher vm = vModelPattern.matcher(attrs);
                        if (vm.find()) {
                            stateVar = vm.group(1);
                        }
                        if (stateVar == null) {
                            Matcher vis = visiblePattern.matcher(attrs);
                            if (vis.find()) {
                                stateVar = vis.group(1);
                            }
                        }

                        // 关联触发器：取 stateVar 中的变量名匹配 @click 赋值
                        String trigger = null;
                        if (stateVar != null) {
                            Matcher sv = wordPattern.matcher(stateVar);
                            if (sv.find()) {
                                trigger = triggers.get(sv.group(1));
                            }
                        }

                        Map<String, Object> state = new HashMap<>();
                        state.put("component", component);
                        state.put("type", types[t]);
                        state.put("stateVar", stateVar);
                        state.put("trigger", trigger);
                        state.put("file", file.getName());
                        states.add(state);
                    }
                }
            }
        } catch (Exception e) {
            // 失败时返回空列表，不阻断分析
        }
        return states;
    }

    /**
     * 提取 template 中的 DOM 选择器（data-testid/id/ref/aria-label）。
     */
    private List<Map<String, Object>> extractDomSelectors(File dir) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<File> vueFiles = new ArrayList<>();
            collectVueFiles(dir, vueFiles);

            // {类型, 正则} 配对
            String[][] specs = {
                    {"data-testid", "\\bdata-testid\\s*=\\s*\"([^\"]+)\""},
                    {"id", "\\bid\\s*=\\s*\"([^\"]+)\""},
                    {"ref", "\\bref\\s*=\\s*\"([^\"]+)\""},
                    {"aria-label", "\\baria-label\\s*=\\s*\"([^\"]+)\""}
            };

            for (File file : vueFiles) {
                String content = readFile(file);
                if (content == null) {
                    continue;
                }

                // 仅在 template 区域内匹配，天然排除 style/script 标签中的 id
                int[] range = templateRange(content);
                int searchStart = (range != null) ? range[0] : 0;
                int searchEnd = (range != null) ? range[1] : content.length();

                String component = stripExtension(file.getName());
                List<Map<String, Object>> selectors = new ArrayList<>();

                for (String[] spec : specs) {
                    String type = spec[0];
                    Pattern p = Pattern.compile(spec[1]);
                    Matcher m = p.matcher(content);
                    while (m.find()) {
                        if (m.start() < searchStart || m.end() > searchEnd) {
                            continue;
                        }
                        String element = findEnclosingElement(content, m.start());
                        // 保险：排除 style/script 标签
                        if (type.equals("id") && (element.equals("style") || element.equals("script"))) {
                            continue;
                        }
                        Map<String, Object> sel = new HashMap<>();
                        sel.put("type", type);
                        sel.put("value", m.group(1));
                        sel.put("element", element);
                        selectors.add(sel);
                    }
                }

                if (selectors.isEmpty()) {
                    continue;
                }

                Map<String, Object> entry = new HashMap<>();
                entry.put("component", component);
                entry.put("selectors", selectors);
                entry.put("file", file.getName());
                result.add(entry);
            }
        } catch (Exception e) {
            // 失败时返回空列表，不阻断分析
        }
        return result;
    }

    /**
     * 提取页面跳转关系（router.push / router-link）。
     */
    private List<Map<String, Object>> extractPageFlows(File dir) {
        List<Map<String, Object>> flows = new ArrayList<>();
        try {
            // 扫描 .vue 和 .js/.ts 文件
            List<File> files = new ArrayList<>();
            collectScriptFiles(dir, files);

            // 构建组件名 -> 路由 path 映射，用于推断 from
            Map<String, String> componentToPath = buildComponentToPathMap(dir);

            Pattern routerPushStr = Pattern.compile("router\\.push\\(\\s*['\"]([^'\"]+)['\"]");
            Pattern routerPushObj = Pattern.compile(
                    "router\\.push\\(\\s*\\{\\s*path\\s*:\\s*['\"]([^'\"]+)['\"]");
            Pattern routerLink = Pattern.compile("<router-link\\b[^>]*\\bto\\s*=\\s*\"([^\"]+)\"");
            Pattern clickPattern = Pattern.compile("@click=\"([^\"]+)\"");
            Pattern fnPattern = Pattern.compile("(?:function|const|let|var)\\s+(\\w+)\\b");

            for (File file : files) {
                String content = readFile(file);
                if (content == null) {
                    continue;
                }
                String component = stripExtension(file.getName());
                String from = componentToPath.getOrDefault(component, "");

                Matcher m1 = routerPushStr.matcher(content);
                while (m1.find()) {
                    addPageFlow(flows, from, m1.group(1),
                            inferTrigger(content, m1.start(), clickPattern, fnPattern),
                            component, file.getName());
                }

                Matcher m2 = routerPushObj.matcher(content);
                while (m2.find()) {
                    addPageFlow(flows, from, m2.group(1),
                            inferTrigger(content, m2.start(), clickPattern, fnPattern),
                            component, file.getName());
                }

                Matcher m3 = routerLink.matcher(content);
                while (m3.find()) {
                    addPageFlow(flows, from, m3.group(1), "link", component, file.getName());
                }
            }
        } catch (Exception e) {
            // 失败时返回空列表，不阻断分析
        }
        return flows;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从文件名中去掉扩展名作为组件名：LoginForm.vue -> LoginForm
     */
    private String stripExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 提取配对括号内的内容（支持嵌套，忽略字符串字面量中的括号）。
     *
     * @param content  原文
     * @param openIdx  开始括号的索引
     * @param open     开始括号字符，如 '{' 或 '['
     * @param close    结束括号字符，如 '}' 或 ']'
     * @return 括号内部子串（不含外层括号）；若无法配对返回 null
     */
    private String extractBalanced(String content, int openIdx, char open, char close) {
        if (openIdx < 0 || openIdx >= content.length() || content.charAt(openIdx) != open) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        char strQuote = 0;
        for (int i = openIdx; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == strQuote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                strQuote = c;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return content.substring(openIdx + 1, i);
                }
            }
        }
        return null;
    }

    /**
     * 返回 template 区域的 [start, end) 索引；若不存在返回 null。
     */
    private int[] templateRange(String content) {
        Matcher ts = Pattern.compile("<template\\b[^>]*>").matcher(content);
        if (!ts.find()) {
            return null;
        }
        int start = ts.end();
        int end = content.lastIndexOf("</template>");
        if (end < 0 || end <= start) {
            return null;
        }
        return new int[]{start, end};
    }

    /**
     * 从匹配位置向前查找最近的开始标签名，作为所属元素。
     */
    private String findEnclosingElement(String content, int matchStart) {
        int i = matchStart - 1;
        while (i >= 0) {
            int lt = content.lastIndexOf('<', i);
            if (lt < 0) {
                return "";
            }
            // 跳过闭合标签 </xxx>
            if (lt + 1 < content.length() && content.charAt(lt + 1) == '/') {
                i = lt - 1;
                continue;
            }
            // < 后跟字母则为开始标签
            if (lt + 1 < content.length() && Character.isLetter(content.charAt(lt + 1))) {
                int end = lt + 1;
                while (end < content.length()
                        && (Character.isLetterOrDigit(content.charAt(end)) || content.charAt(end) == '-')) {
                    end++;
                }
                return content.substring(lt + 1, end);
            }
            i = lt - 1;
        }
        return "";
    }

    /**
     * 推断跳转触发器：优先取最近的 @click 处理器，其次取最近的函数定义名。
     */
    private String inferTrigger(String content, int matchStart, Pattern clickPattern, Pattern fnPattern) {
        int windowStart = Math.max(0, matchStart - 300);
        String window = content.substring(windowStart, matchStart);

        Matcher cm = clickPattern.matcher(window);
        String lastClick = null;
        while (cm.find()) {
            lastClick = cm.group(1).trim();
        }
        if (lastClick != null) {
            return lastClick;
        }

        Matcher fm = fnPattern.matcher(window);
        String lastFn = null;
        while (fm.find()) {
            lastFn = fm.group(1);
        }
        if (lastFn != null) {
            return lastFn;
        }
        return "unknown";
    }

    /**
     * 添加一条页面跳转记录。
     */
    private void addPageFlow(List<Map<String, Object>> flows, String from, String to,
                             String trigger, String component, String file) {
        Map<String, Object> flow = new HashMap<>();
        flow.put("from", from);
        flow.put("to", to);
        flow.put("trigger", trigger);
        flow.put("component", component);
        flow.put("file", file);
        flows.add(flow);
    }

    /**
     * 解析路由文件，构建组件名 -> 路由 path 的映射。
     * 支持两种写法：
     *   component: () => import('@/views/LoginForm.vue')
     *   component: LoginForm
     */
    private Map<String, String> buildComponentToPathMap(File dir) {
        Map<String, String> map = new HashMap<>();
        List<File> routerFiles = findRouterFiles(dir);
        Pattern pathPattern = Pattern.compile("path\\s*:\\s*['\"]([^'\"]+)['\"]");
        Pattern componentPattern = Pattern.compile(
                "component\\s*:\\s*(?:\\(\\)\\s*=>\\s*import\\s*\\(\\s*['\"]([^'\"]+)['\"]|([A-Za-z_$][\\w$]*))");

        for (File file : routerFiles) {
            String content = readFile(file);
            if (content == null) {
                continue;
            }
            Matcher pm = pathPattern.matcher(content);
            while (pm.find()) {
                String path = pm.group(1);
                // 在 path 之后 400 字符内查找 component
                int searchEnd = Math.min(content.length(), pm.end() + 400);
                String region = content.substring(pm.end(), searchEnd);
                Matcher cm = componentPattern.matcher(region);
                if (cm.find()) {
                    String comp = cm.group(1) != null ? cm.group(1) : cm.group(2);
                    if (comp == null) {
                        continue;
                    }
                    // 取 basename 并去掉扩展名
                    String base = comp;
                    int slash = Math.max(comp.lastIndexOf('/'), comp.lastIndexOf('\\'));
                    if (slash >= 0) {
                        base = comp.substring(slash + 1);
                    }
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) {
                        base = base.substring(0, dot);
                    }
                    map.putIfAbsent(base, path);
                }
            }
        }
        return map;
    }

    // ==================== v1.12 LLM 增强方法 ====================

    // v1.12: 用 LLM 补充正则遗漏的前端分析结果
    private void enhanceWithLlm(List<Map<String, Object>> forms,
                                List<Map<String, Object>> componentStates,
                                List<Map<String, Object>> domSelectors,
                                List<Map<String, Object>> pageFlows,
                                File dir) {
        // 1. 收集 .vue 文件源码摘要
        String sourceSnippets = collectSourceSnippets(dir);
        if (sourceSnippets.isBlank()) return;

        // 2. 构建 prompt
        String systemPrompt = "你是前端代码分析专家。正则已提取了部分结果，请你阅读源码，补充正则遗漏的内容。\n"
            + "只返回正则没提取到的，不要重复已有结果。\n"
            + "返回纯 JSON（不要 markdown 代码块）：\n"
            + "{\"supplementalForms\":[{\"component\":\"\",\"fields\":[{\"name\":\"\",\"type\":\"\",\"label\":\"\",\"required\":false,\"rules\":[]}],\"file\":\"\"}],"
            + "\"supplementalStates\":[{\"component\":\"\",\"type\":\"\",\"stateVar\":\"\",\"trigger\":\"\",\"file\":\"\"}],"
            + "\"supplementalSelectors\":[{\"component\":\"\",\"selectors\":[{\"type\":\"\",\"value\":\"\",\"element\":\"\"}],\"file\":\"\"}],"
            + "\"supplementalFlows\":[{\"from\":\"\",\"to\":\"\",\"trigger\":\"\",\"component\":\"\",\"file\":\"\"}]}";

        // 构建正则已有结果的摘要
        StringBuilder regexSummary = new StringBuilder();
        regexSummary.append("正则已提取结果：\n");
        regexSummary.append("forms: ").append(forms.size()).append(" 个\n");
        // 列出已有 component 名，让 LLM 知道哪些已提取
        forms.forEach(f -> regexSummary.append("  - ").append(f.get("component")).append("\n"));
        regexSummary.append("componentStates: ").append(componentStates.size()).append(" 个\n");
        componentStates.forEach(s -> regexSummary.append("  - ").append(s.get("component")).append(":").append(s.get("type")).append("\n"));
        regexSummary.append("domSelectors: ").append(domSelectors.size()).append(" 个\n");
        domSelectors.forEach(s -> regexSummary.append("  - ").append(s.get("component")).append("\n"));
        regexSummary.append("pageFlows: ").append(pageFlows.size()).append(" 个\n");

        String userPrompt = regexSummary.toString() + "\n源码摘要：\n" + sourceSnippets
            + "\n\n请分析源码，补充正则遗漏的表单字段、组件交互状态、DOM选择器、页面跳转。只返回补充内容，不要重复已有的。";

        // 3. 调 LLM
        String response = llmService.chat(systemPrompt, userPrompt, 0.3);

        // 4. 解析 JSON 并合并
        parseAndMergeSupplements(response, forms, componentStates, domSelectors, pageFlows);
    }

    // v1.12: 收集 .vue 文件源码摘要，每文件截断 1500 字符，总计上限 12000 字符
    private String collectSourceSnippets(File dir) {
        List<File> vueFiles = new ArrayList<>();
        collectVueFiles(dir, vueFiles);

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        int maxTotal = 12000;

        for (File file : vueFiles) {
            if (totalChars >= maxTotal) break;
            try {
                String content = readFile(file);
                String component = file.getName().replace(".vue", "");

                // 截取 template 部分（最多 800 字符）
                int templateStart = content.indexOf("<template>");
                int templateEnd = content.indexOf("</template>");
                String template = "";
                if (templateStart >= 0 && templateEnd > templateStart) {
                    template = content.substring(templateStart, Math.min(templateEnd + 11, templateStart + 811));
                }

                // 截取 script 部分（最多 700 字符）
                int scriptStart = content.indexOf("<script");
                int scriptEnd = content.indexOf("</script>");
                String script = "";
                if (scriptStart >= 0 && scriptEnd > scriptStart) {
                    script = content.substring(scriptStart, Math.min(scriptEnd + 9, scriptStart + 709));
                }

                String snippet = "=== " + component + ".vue ===\n" + template + "\n" + script + "\n\n";
                if (totalChars + snippet.length() > maxTotal) {
                    snippet = snippet.substring(0, maxTotal - totalChars);
                }
                sb.append(snippet);
                totalChars += snippet.length();
            } catch (Exception e) {
                // 跳过读取失败的文件
            }
        }
        return sb.toString();
    }

    // v1.12: 解析 LLM 返回的补充结果并合并到正则结果中
    @SuppressWarnings("unchecked")
    private void parseAndMergeSupplements(String response,
                                           List<Map<String, Object>> forms,
                                           List<Map<String, Object>> componentStates,
                                           List<Map<String, Object>> domSelectors,
                                           List<Map<String, Object>> pageFlows) {
        // 提取 JSON（可能被包在 markdown 代码块中）
        String json = response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}");
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }

        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            log.warn("Failed to parse LLM supplement JSON: {}", e.getMessage());
            return;
        }

        // 合并 forms（按 component 去重）
        if (root.has("supplementalForms") && root.get("supplementalForms").isArray()) {
            for (JsonNode node : root.get("supplementalForms")) {
                String component = node.path("component").asText("");
                if (component.isEmpty()) continue;
                // 检查是否已存在
                boolean exists = forms.stream()
                    .anyMatch(f -> component.equals(f.get("component")));
                if (!exists) {
                    Map<String, Object> form = new LinkedHashMap<>();
                    form.put("component", component);
                    form.put("fields", parseFields(node.path("fields")));
                    form.put("file", node.path("file").asText(""));
                    forms.add(form);
                    log.info("LLM supplemented form: {}", component);
                }
            }
        }

        // 合并 componentStates（按 component + type 去重）
        if (root.has("supplementalStates") && root.get("supplementalStates").isArray()) {
            for (JsonNode node : root.get("supplementalStates")) {
                String component = node.path("component").asText("");
                String type = node.path("type").asText("");
                if (component.isEmpty() || type.isEmpty()) continue;
                boolean exists = componentStates.stream()
                    .anyMatch(s -> component.equals(s.get("component")) && type.equals(s.get("type")));
                if (!exists) {
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("component", component);
                    state.put("type", type);
                    state.put("stateVar", node.path("stateVar").asText(""));
                    state.put("trigger", node.path("trigger").asText(""));
                    state.put("file", node.path("file").asText(""));
                    componentStates.add(state);
                    log.info("LLM supplemented state: {} ({})", component, type);
                }
            }
        }

        // 合并 domSelectors（按 component 去重）
        if (root.has("supplementalSelectors") && root.get("supplementalSelectors").isArray()) {
            for (JsonNode node : root.get("supplementalSelectors")) {
                String component = node.path("component").asText("");
                if (component.isEmpty()) continue;
                boolean exists = domSelectors.stream()
                    .anyMatch(s -> component.equals(s.get("component")));
                if (!exists) {
                    Map<String, Object> sel = new LinkedHashMap<>();
                    sel.put("component", component);
                    sel.put("selectors", parseSelectors(node.path("selectors")));
                    sel.put("file", node.path("file").asText(""));
                    domSelectors.add(sel);
                    log.info("LLM supplemented selectors: {}", component);
                }
            }
        }

        // 合并 pageFlows（按 from + to 去重）
        if (root.has("supplementalFlows") && root.get("supplementalFlows").isArray()) {
            for (JsonNode node : root.get("supplementalFlows")) {
                String from = node.path("from").asText("");
                String to = node.path("to").asText("");
                if (from.isEmpty() || to.isEmpty()) continue;
                boolean exists = pageFlows.stream()
                    .anyMatch(f -> from.equals(f.get("from")) && to.equals(f.get("to")));
                if (!exists) {
                    Map<String, Object> flow = new LinkedHashMap<>();
                    flow.put("from", from);
                    flow.put("to", to);
                    flow.put("trigger", node.path("trigger").asText(""));
                    flow.put("component", node.path("component").asText(""));
                    flow.put("file", node.path("file").asText(""));
                    pageFlows.add(flow);
                    log.info("LLM supplemented flow: {} -> {}", from, to);
                }
            }
        }
    }

    // v1.12: 解析 LLM 返回的 fields 数组
    private List<Map<String, Object>> parseFields(JsonNode fieldsNode) {
        List<Map<String, Object>> fields = new ArrayList<>();
        if (fieldsNode != null && fieldsNode.isArray()) {
            for (JsonNode f : fieldsNode) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("name", f.path("name").asText(""));
                field.put("type", f.path("type").asText("input"));
                field.put("label", f.path("label").asText(""));
                field.put("required", f.path("required").asBoolean(false));
                List<String> rules = new ArrayList<>();
                if (f.has("rules") && f.get("rules").isArray()) {
                    for (JsonNode r : f.get("rules")) {
                        rules.add(r.asText());
                    }
                }
                field.put("rules", rules);
                fields.add(field);
            }
        }
        return fields;
    }

    // v1.12: 解析 LLM 返回的 selectors 数组
    private List<Map<String, Object>> parseSelectors(JsonNode selectorsNode) {
        List<Map<String, Object>> selectors = new ArrayList<>();
        if (selectorsNode != null && selectorsNode.isArray()) {
            for (JsonNode s : selectorsNode) {
                Map<String, Object> sel = new LinkedHashMap<>();
                sel.put("type", s.path("type").asText(""));
                sel.put("value", s.path("value").asText(""));
                sel.put("element", s.path("element").asText(""));
                selectors.add(sel);
            }
        }
        return selectors;
    }
}
