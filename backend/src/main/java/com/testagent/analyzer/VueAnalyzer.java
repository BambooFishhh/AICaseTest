package com.testagent.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.BusinessComponentPolicy;
import com.testagent.service.LlmResultCacheService;
import com.testagent.service.LlmService;
import com.testagent.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class VueAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(VueAnalyzer.class);

    @Autowired
    private LlmService llmService;

    // v7.5(A11): 组件摘要 LLM 结果缓存——按 prompt hash（含组件源码）缓存，
    // 文件没变不重复调 LLM（分析是高频操作，此前成本线性放大）
    @Autowired
    private LlmResultCacheService llmResultCacheService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private BusinessComponentPolicy businessComponentPolicy;

    @Value("${app.executor.llm-concurrency:4}")
    private int llmConcurrency;

    private static final Pattern VUE_VERSION_PATTERN =
            Pattern.compile("\"vue\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ROUTE_PATTERN = Pattern.compile(
            "path\\s*:\\s*['\"]([^'\"]+)['\"](?:[\\s\\S]{0,300}?name\\s*:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern URL_METHOD_PATTERN = Pattern.compile(
            "url\\s*:\\s*['\"]([^'\"]+)['\"](?:[\\s\\S]{0,200}?method\\s*:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern AXIOS_PATTERN = Pattern.compile(
            "\\.(get|post|put|delete|patch)\\s*\\(\\s*['\"]([^'\"]+)['\"]");

    // v7.6(G20层3): 前端用户反馈文案——ElMessage.error("...") / Message.error(...) / this.$message.error(...)
    private static final Pattern FEEDBACK_TEXT_PATTERN = Pattern.compile(
            "(?:ElMessage|Message|\\$message)\\.(error|success|warning|info)\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]");

    public FrontendResult analyze(String frontendDir) {
        File dir = new File(frontendDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return FrontendResult.skipped();
        }

        Map<String, Object> techStack = detectTechStack(dir);
        // v7.4(C1): 分析告警收集（VueAnalyzer 为单例，多项目可能并发 analyze——必须参数传递，不得用实例字段）
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> routes = extractRoutes(dir);
        List<Map<String, Object>> apiCalls = extractApiCalls(dir, warnings);
        int fileCount = countSourceFiles(dir);

        // v1.11: 深度提取表单、组件状态、DOM 选择器、页面跳转
        List<Map<String, Object>> forms = extractForms(dir, warnings);
        List<Map<String, Object>> componentStates = extractComponentStates(dir, warnings);
        List<Map<String, Object>> domSelectors = extractDomSelectors(dir, warnings);
        List<Map<String, Object>> pageFlows = extractPageFlows(dir, warnings);

        // v7.6(G20层3): 用户反馈文案（错误→用户文案对照表的前端侧）
        List<Map<String, Object>> userFeedbackTexts = extractFeedbackTexts(dir, warnings);

        // v6.1 (Agentic RAG): 逐组件语义摘要 + 按需源码片段 + 业务分，供前端组件级索引
        List<Map<String, Object>> componentSummaries = new ArrayList<>();
        try {
            componentSummaries = extractComponentSummaries(dir, forms, componentStates,
                    domSelectors, pageFlows, warnings);
        } catch (Exception e) {
            log.warn("Component summary extraction failed: {}", e.getMessage());
            warnings.add("组件语义摘要提取失败（" + e.getMessage() + "），componentSummaries 结果可能不完整");
        }

        // v1.12: LLM 补充正则遗漏的内容
        if (llmService.isConfigured()) {
            try {
                enhanceWithLlm(forms, componentStates, domSelectors, pageFlows, dir, warnings);
            } catch (Exception e) {
                log.warn("LLM enhancement failed, using regex-only results: {}", e.getMessage());
                warnings.add("LLM 前端增强失败（" + e.getMessage() + "），结果仅含正则提取部分");
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
                .componentSummaries(componentSummaries)
                .userFeedbackTexts(userFeedbackTexts)
                .fileCount(fileCount)
                .status("ok")
                .warnings(warnings)
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

    /**
     * v7.10(A12): apiCalls 扫描范围从 src/api 目录扩到全部 .vue/.js/.ts——
     * 组件内直书 axios.get('/api/order/' + id) 此前全漏，状态机跨端证据不完整。
     * 按 (method+url) 去重，上限 100 条，超限记 warning（纯规则层正则，全量扫描换取证据完整性）。
     * v7.10(A12): 包级可见，供单测直接验证全量扫描语义
     */
    List<Map<String, Object>> extractApiCalls(File frontendDir, List<String> warnings) {
        List<Map<String, Object>> apiCalls = new ArrayList<>();
        List<File> scriptFiles = new ArrayList<>();
        collectScriptFiles(frontendDir, scriptFiles);

        Set<String> seen = new LinkedHashSet<>();
        for (File file : scriptFiles) {
            String content = readFile(file);
            if (content == null) {
                continue;
            }
            Matcher urlMatcher = URL_METHOD_PATTERN.matcher(content);
            while (urlMatcher.find()) {
                String url = urlMatcher.group(1);
                String method = urlMatcher.group(2) != null ? urlMatcher.group(2) : "unknown";
                if (!seen.add(method + "|" + url)) {
                    continue;
                }
                Map<String, Object> call = new HashMap<>();
                call.put("url", url);
                call.put("method", method);
                call.put("file", file.getName());
                apiCalls.add(call);
            }
            Matcher axiosMatcher = AXIOS_PATTERN.matcher(content);
            while (axiosMatcher.find()) {
                String method = axiosMatcher.group(1);
                String url = axiosMatcher.group(2);
                if (!seen.add(method + "|" + url)) {
                    continue;
                }
                Map<String, Object> call = new HashMap<>();
                call.put("url", url);
                call.put("method", method);
                call.put("file", file.getName());
                apiCalls.add(call);
            }
        }
        if (apiCalls.size() > 100) {
            warnings.add("apiCalls 超上限 " + apiCalls.size() + " 条，截断为 100 条");
            return new ArrayList<>(apiCalls.subList(0, 100));
        }
        return apiCalls;
    }

    /**
     * v7.6(G20层3): 提取用户反馈文案——ElMessage.error("删除成功") / Message.success(...) / this.$message.warning(...)
     * 调用的字符串字面量。扫描全部 .vue/.js/.ts（跳过 node_modules/dist/.git），
     * 按 (type+text) 去重，上限 100 条，超限记 warning。
     * 价值：expected 的"错误→用户文案"对照表——LLM 生成 UI 现象形预期结果的可翻译素材。
     */
    List<Map<String, Object>> extractFeedbackTexts(File frontendDir, List<String> warnings) {
        List<Map<String, Object>> texts = new ArrayList<>();
        List<File> scriptFiles = new ArrayList<>();
        collectScriptFiles(frontendDir, scriptFiles);
        Set<String> seen = new LinkedHashSet<>();
        for (File file : scriptFiles) {
            String content = readFile(file);
            if (content == null) {
                continue;
            }
            Matcher m = FEEDBACK_TEXT_PATTERN.matcher(content);
            while (m.find()) {
                String type = m.group(1);
                String text = m.group(2).trim();
                if (text.isEmpty() || text.length() > 100) {
                    continue;
                }
                if (!seen.add(type + "|" + text)) {
                    continue;
                }
                Map<String, Object> item = new HashMap<>();
                item.put("type", type);
                item.put("text", text);
                item.put("file", file.getName());
                texts.add(item);
            }
        }
        if (texts.size() > 100) {
            warnings.add("用户反馈文案超上限 " + texts.size() + " 条，截断为 100 条");
            return new ArrayList<>(texts.subList(0, 100));
        }
        return texts;
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
     * 递归收集所有 .vue 文件（跳过 node_modules）。
     * v7.4(A9): 递归出口按绝对路径字典序排序——File.listFiles() 顺序依赖操作系统，
     * 此前两次分析 LLM 看到的文件集合（12k 截断子集）不同，结果漂移。
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
        result.sort(Comparator.comparing(File::getAbsolutePath));
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
    private List<Map<String, Object>> extractForms(File dir, List<String> warnings) {
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
                    // v7.4(C1): 文件读取失败不再静默跳过
                    warnings.add("Vue 文件读取失败: " + file.getName());
                    continue;
                }
                String component = stripExtension(file.getName());

                // 解析 rules 对象块（处理嵌套大括号）
                // v7.4(A8): 收集文件内全部 rules 块并合并——此前 rs.find() 只取第一个，
                // 一个 .vue 多表单/多 rules 对象时后续字段校验全部丢失或配错
                List<String> ruleBlocks = new ArrayList<>();
                Matcher rs = rulesStartPattern.matcher(content);
                while (rs.find()) {
                    int openIdx = rs.end() - 1; // 指向 '{'
                    String block = extractBalanced(content, openIdx, '{', '}');
                    if (block != null) {
                        ruleBlocks.add(block);
                    } else {
                        // v7.4(C1): rules 块括号不配对（截半）不再静默丢失
                        warnings.add("rules 块括号不配对，该块校验规则丢失: " + file.getName());
                    }
                }
                String rulesBlock = ruleBlocks.isEmpty() ? null : String.join("\n", ruleBlocks);
                if (ruleBlocks.size() > 1) {
                    warnings.add("检测到 " + ruleBlocks.size() + " 个 rules 块，已全部合并解析: " + file.getName());
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
            // v7.4(C1): 整体失败不再静默——"0 个表单"无从区分真没有还是解析失败
            warnings.add("表单提取失败（" + e.getMessage() + "），forms 结果可能不完整");
            log.warn("extractForms failed: {}", e.getMessage());
        }
        return forms;
    }

    /**
     * 提取组件交互状态（弹窗/抽屉/分步/标签页）。
     */
    private List<Map<String, Object>> extractComponentStates(File dir, List<String> warnings) {
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
            // v7.4(C1): 提取失败写入 warnings，"0 个组件状态"可区分真没有还是解析失败
            warnings.add("组件交互状态提取失败（" + e.getMessage() + "），componentStates 结果可能不完整");
            log.warn("extractComponentStates failed: {}", e.getMessage());
        }
        return states;
    }

    /**
     * 提取 template 中的 DOM 选择器（data-testid/id/ref/aria-label）。
     */
    private List<Map<String, Object>> extractDomSelectors(File dir, List<String> warnings) {
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
            // v7.4(C1): 提取失败写入 warnings
            warnings.add("DOM 选择器提取失败（" + e.getMessage() + "），domSelectors 结果可能不完整");
            log.warn("extractDomSelectors failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 提取页面跳转关系（router.push / router-link）。
     */
    private List<Map<String, Object>> extractPageFlows(File dir, List<String> warnings) {
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
            // v7.4(C1): 提取失败写入 warnings
            warnings.add("页面跳转提取失败（" + e.getMessage() + "），pageFlows 结果可能不完整");
            log.warn("extractPageFlows failed: {}", e.getMessage());
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
     * v7.4(A7): 增加模板字符串（反引号）支持——Vue rules 常见 `` message: `长度需在 ${min}~${max}` ``
     * 导致花括号计数错位、rules 块静默截半。重写为状态机：
     * - 代码态：引号/反引号进入字符串态，括号计数（原行为）
     * - 字符串态：转义跳过，匹配引号退出
     * - 模板态：反引号退出；"${" 进入模板表达式态（depth=1）
     * - 模板表达式态：可嵌套普通字符串/模板字符串（栈式）；'{' depth++，'}' depth-- 归零退回模板态；
     *   表达式内的花括号不计入外层计数（模板串对 rules 块而言是不透明字符串）
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
        int n = content.length();
        // 状态栈：'s'=普通字符串(value=引号char)，'t'=模板字符串，'e'=模板表达式(value=花括号深度int)
        java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>();
        int i = openIdx;
        while (i < n) {
            char c = content.charAt(i);
            Object[] top = stack.peek();
            if (top == null) {
                // 代码态
                if (c == '"' || c == '\'') {
                    stack.push(new Object[]{'s', c});
                    i++;
                } else if (c == '`') {
                    stack.push(new Object[]{'t', null});
                    i++;
                } else if (c == open) {
                    depth++;
                    i++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return content.substring(openIdx + 1, i);
                    }
                    i++;
                } else {
                    i++;
                }
                continue;
            }
            char kind = (Character) top[0];
            if (kind == 's') {
                char quote = (Character) top[1];
                if (c == '\\') {
                    i += 2; // 跳过转义字符
                } else if (c == quote) {
                    stack.pop();
                    i++;
                } else {
                    i++;
                }
            } else if (kind == 't') {
                if (c == '\\') {
                    i += 2;
                } else if (c == '`') {
                    stack.pop();
                    i++;
                } else if (c == '$' && i + 1 < n && content.charAt(i + 1) == '{') {
                    stack.push(new Object[]{'e', 1});
                    i += 2;
                } else {
                    i++;
                }
            } else {
                // 模板表达式态
                int braceDepth = (Integer) top[1];
                if (c == '"' || c == '\'') {
                    stack.push(new Object[]{'s', c});
                    i++;
                } else if (c == '`') {
                    stack.push(new Object[]{'t', null});
                    i++;
                } else if (c == '{') {
                    top[1] = braceDepth + 1;
                    i++;
                } else if (c == '}') {
                    if (braceDepth <= 1) {
                        stack.pop(); // ${ 表达式结束，退回模板态
                    } else {
                        top[1] = braceDepth - 1;
                    }
                    i++;
                } else {
                    i++;
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
    // v6.1 (Agentic RAG): 逐组件生成语义摘要。确定性基线 + LLM 语义增强（完整读文件，不截断）。
    private List<Map<String, Object>> extractComponentSummaries(File dir,
                                                                List<Map<String, Object>> forms,
                                                                List<Map<String, Object>> componentStates,
                                                                List<Map<String, Object>> domSelectors,
                                                                List<Map<String, Object>> pageFlows,
                                                                List<String> warnings) {
        List<File> vueFiles = new ArrayList<>();
        collectVueFiles(dir, vueFiles);
        Map<String, String> componentToPath = buildComponentToPathMap(dir);
        Map<String, List<Map<String, Object>>> formsByComp = groupByComponent(forms);
        Map<String, List<Map<String, Object>>> statesByComp = groupByComponent(componentStates);
        Map<String, List<Map<String, Object>>> selectorsByComp = groupByComponent(domSelectors);
        Map<String, List<Map<String, Object>>> flowsByComp = groupByComponent(pageFlows);

        List<Map<String, Object>> out = new ArrayList<>();
        int limit = 200;
        for (File file : vueFiles) {
            if (out.size() >= limit) {
                break;
            }
            try {
                String rel = relativize(dir, file);
                String comp = stripExtension(file.getName());
                String content = readFile(file);
                if (content == null || content.isBlank()) {
                    continue;
                }
                List<String> interactions = extractInteractions(content);
                List<String> stateOps = extractStateOps(content);
                List<String> navigations = extractNavigations(content);
                List<String> apiCalls = extractComponentApiCalls(content);
                List<String> keywords = extractKeywords(comp, rel, interactions, apiCalls, navigations);

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "comp-" + sanitizeId(rel));
                m.put("component", comp);
                m.put("file", rel);
                m.put("route", componentToPath.getOrDefault(comp, ""));
                m.put("interactions", interactions);
                m.put("stateOps", stateOps);
                m.put("routeNavigations", navigations);
                m.put("apiCalls", apiCalls);
                m.put("keywords", keywords);
                m.put("snippet", extractComponentSnippet(content));
                m.put("businessScore", businessScore(rel, comp,
                        componentToPath.getOrDefault(comp, ""),
                        interactions, stateOps, navigations, apiCalls,
                        formsByComp.getOrDefault(comp, List.of()),
                        statesByComp.getOrDefault(comp, List.of())));
                m.put("summary", buildBaselineSummary(comp, rel,
                        formsByComp.getOrDefault(comp, List.of()),
                        statesByComp.getOrDefault(comp, List.of()),
                        selectorsByComp.getOrDefault(comp, List.of()),
                        flowsByComp.getOrDefault(comp, List.of()),
                        interactions, stateOps, navigations, apiCalls));
                out.add(m);
            } catch (Exception e) {
                log.debug("Component summary failed for {}: {}", file.getName(), e.getMessage());
            }
        }

        // v6.2: 逐组件 LLM 增强并行化——串行 N 次调用是分析最大瓶颈，改有界并发。
        // 注意：不能复用 analysisExecutor（core=2 且父线程 join 阻塞时线程池不扩线程，结果串行），
        // 必须用按并发数建好的专用固定线程池，才能保证真的有 llm-concurrency 路并发。
        // v6.3fix: 仅对业务组件（businessScore >= 0）跑 LLM 摘要；公共/布局组件（BackToTop/Breadcrumb
        // 等）保留确定性基线摘要即可，不再每次白白消耗一次 LLM 调用（前端阶段是分析的最大耗时来源）。
        List<Map<String, Object>> businessComponents = new ArrayList<>();
        for (Map<String, Object> m : out) {
            if (isBusinessComponent(m)) {
                businessComponents.add(m);
            }
        }
        if (llmService.isConfigured() && !businessComponents.isEmpty()) {
            long t0 = System.currentTimeMillis();
            int workers = Math.max(1, llmConcurrency);
            // v7.4(C1): 组件摘要 LLM 失败计数（线程安全，聚合为一条 warning）
            java.util.concurrent.atomic.AtomicInteger summaryFailures = new java.util.concurrent.atomic.AtomicInteger();
            // 把外层分析子线程上的埋点上下文与 phase 传播到组件并发池，使 LLM token 也能落库。
            TelemetryService.TelemetryContext telemetryCtx = telemetryService.currentContext();
            String telemetryPhase = telemetryService.currentPhaseOverride();
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (Map<String, Object> m : businessComponents) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        telemetryService.bindPhase(telemetryCtx, telemetryPhase, () -> {
                            try {
                                enhanceComponentSummary(dir, m);
                            } catch (Exception e) {
                                summaryFailures.incrementAndGet();
                                log.warn("LLM component summary failed for {}: {}",
                                        m.get("component"), e.getMessage());
                            }
                            return null;
                        });
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                pool.shutdown();
            }
            int failed = summaryFailures.get();
            if (failed > 0) {
                warnings.add("组件语义摘要 LLM 增强失败 " + failed + "/" + businessComponents.size()
                        + " 个（对应组件保留确定性基线摘要）");
            }
            log.info("Parallel LLM component summaries: {} business / {} total components, concurrency={}, took {}ms",
                    businessComponents.size(), out.size(), workers, System.currentTimeMillis() - t0);
        }
        return out;
    }

    // v6.3/v6.6: 只有足够强的业务信号才触发 LLM 摘要；低于阈值判为通用组件，仅保留确定性基线。
    private boolean isBusinessComponent(Map<String, Object> m) {
        // v6.6: 与 TestGeneratorAgent 共用统一阈值。解析失败默认排除，不再误判为业务组件。
        return businessComponentPolicy.needsLlmSummary(m);
    }

    private Map<String, List<Map<String, Object>>> groupByComponent(List<Map<String, Object>> items) {
        Map<String, List<Map<String, Object>>> map = new HashMap<>();
        if (items == null) {
            return map;
        }
        for (Map<String, Object> item : items) {
            Object comp = item == null ? null : item.get("component");
            String key = comp == null ? "" : String.valueOf(comp);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    private List<String> extractInteractions(String content) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("@(?:click|change|input|submit|blur|keyup)\\s*=\\s*\"([^\"]+)\"")
                .matcher(content);
        while (m.find()) {
            String expr = m.group(1).trim();
            if (!expr.isEmpty() && !out.contains(expr) && out.size() < 20) {
                out.add(expr);
            }
        }
        return out;
    }

    private List<String> extractStateOps(String content) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?::visible|v-if|v-show|v-model)\\s*=\\s*\"([^\"]+)\"")
                .matcher(content);
        while (m.find()) {
            String expr = m.group(1).trim();
            if (!expr.isEmpty() && !out.contains(expr) && out.size() < 20) {
                out.add(expr);
            }
        }
        return out;
    }

    private List<String> extractNavigations(String content) {
        List<String> out = new ArrayList<>();
        Matcher push = Pattern.compile("router\\.push\\(\\s*['\"]?([^'\"\\)]+)").matcher(content);
        while (push.find()) {
            String to = push.group(1).trim();
            if (!to.isEmpty() && !out.contains(to) && out.size() < 20) {
                out.add(to);
            }
        }
        Matcher link = Pattern.compile("<router-link\\b[^>]*\\bto\\s*=\\s*\"([^\"]+)\"").matcher(content);
        while (link.find()) {
            String to = link.group(1);
            if (!to.isEmpty() && !out.contains(to) && out.size() < 20) {
                out.add(to);
            }
        }
        return out;
    }

    private List<String> extractComponentApiCalls(String content) {
        List<String> out = new ArrayList<>();
        Matcher axios = AXIOS_PATTERN.matcher(content);
        while (axios.find()) {
            String url = axios.group(2);
            if (!out.contains(url) && out.size() < 20) {
                out.add(url);
            }
        }
        Matcher urlM = URL_METHOD_PATTERN.matcher(content);
        while (urlM.find()) {
            String url = urlM.group(1);
            if (!out.contains(url) && out.size() < 20) {
                out.add(url);
            }
        }
        return out;
    }

    private List<String> extractKeywords(String comp, String rel, List<String> interactions,
                                         List<String> apiCalls, List<String> navigations) {
        Set<String> stop = Set.of("index", "main", "view", "views", "components", "common",
                "layout", "src", "vue", "js", "ts", "util", "helper", "app", "page", "pages");
        Set<String> set = new LinkedHashSet<>();
        for (String part : (comp + " " + rel).split("[^A-Za-z0-9]+")) {
            String p = part.toLowerCase();
            if (p.length() >= 3 && !stop.contains(p)) {
                set.add(p);
            }
        }
        for (List<String> list : List.of(interactions, apiCalls, navigations)) {
            for (String s : list) {
                for (String part : s.split("[^A-Za-z0-9]+")) {
                    String p = part.toLowerCase();
                    if (p.length() >= 3 && !stop.contains(p)) {
                        set.add(p);
                    }
                }
            }
        }
        List<String> result = new ArrayList<>(set);
        return result.size() > 30 ? result.subList(0, 30) : result;
    }

    private String buildBaselineSummary(String comp, String rel,
                                        List<Map<String, Object>> forms,
                                        List<Map<String, Object>> states,
                                        List<Map<String, Object>> selectors,
                                        List<Map<String, Object>> flows,
                                        List<String> interactions, List<String> stateOps,
                                        List<String> navigations, List<String> apiCalls) {
        StringBuilder sb = new StringBuilder();
        sb.append("组件 ").append(comp).append(" (").append(rel).append(")");
        if (!forms.isEmpty()) {
            sb.append("；表单字段：");
            for (Map<String, Object> f : forms) {
                Object fields = f.get("fields");
                sb.append(fields == null ? "" : fields);
            }
        }
        if (!states.isEmpty()) {
            sb.append("；交互状态：").append(states);
        }
        if (!selectors.isEmpty()) {
            sb.append("；DOM 选择器：").append(selectors);
        }
        if (!flows.isEmpty()) {
            sb.append("；页面跳转：").append(flows);
        }
        if (!interactions.isEmpty()) {
            sb.append("；事件：").append(interactions);
        }
        if (!stateOps.isEmpty()) {
            sb.append("；状态操作：").append(stateOps);
        }
        if (!navigations.isEmpty()) {
            sb.append("；路由跳转：").append(navigations);
        }
        if (!apiCalls.isEmpty()) {
            sb.append("；API 调用：").append(apiCalls);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 1500 ? s.substring(0, 1500) : s;
    }

    private String extractComponentSnippet(String content) {
        int scriptStart = content.indexOf("<script");
        int scriptEnd = content.indexOf("</script>");
        StringBuilder sb = new StringBuilder();
        if (scriptStart >= 0 && scriptEnd > scriptStart) {
            String script = content.substring(scriptStart, Math.min(scriptEnd + 9, scriptStart + 1600));
            sb.append(script);
        }
        int templateStart = content.indexOf("<template>");
        if (templateStart >= 0 && sb.length() < 800) {
            int templateEnd = content.indexOf("</template>");
            if (templateEnd > templateStart) {
                sb.append("\n").append(content.substring(templateStart,
                        Math.min(templateEnd + 11, templateStart + 800)));
            }
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 1200 ? s.substring(0, 1200) : s;
    }

    private static final Set<String> GENERIC_COMPONENT_NAMES = Set.of(
            "backtotop", "pagination", "paginations", "sidebar", "navbar", "breadcrumb",
            "tagsview", "appmain", "settings", "svgicon", "table", "upload", "dialog",
            "icon", "loading", "empty", "blank", "footer", "header", "search");

    // v6.3: 信号打分（反向判定）——默认非业务，有真实业务信号才加分；
    // 共享/通用目录且既无接口调用、又无用户交互的组件判为通用，避免把普通组件都当成业务发 LLM。
    private double businessScore(String rel, String comp, String route,
                                 List<String> interactions, List<String> stateOps,
                                 List<String> navigations, List<String> apiCalls,
                                 List<Map<String, Object>> forms,
                                 List<Map<String, Object>> componentStates) {
        String lower = rel.toLowerCase();
        boolean hasApi = apiCalls != null && !apiCalls.isEmpty();
        boolean hasInteraction = interactions != null && !interactions.isEmpty();
        boolean hasNavigation = navigations != null && !navigations.isEmpty();
        boolean hasForm = forms != null && !forms.isEmpty();
        boolean hasState = componentStates != null && !componentStates.isEmpty();
        boolean isPage = (route != null && !route.isBlank())
                || lower.contains("/views/") || lower.contains("/pages/")
                || lower.contains("/containers/");

        double score = 0.0;
        if (hasApi) score += 0.5;          // 强业务：调用接口
        if (hasInteraction) score += 0.3;  // 用户流：交互事件
        if (hasNavigation) score += 0.2;   // 流转：路由跳转
        if (hasForm) score += 0.3;         // 表单
        if (hasState) score += 0.1;        // 状态/弹窗控制
        if (isPage) score += 0.2;          // 页面/路由组件

        boolean underShared = lower.matches(".*/components(/[^/]*)?$")
                || lower.contains("/components/layout/") || lower.contains("/components/common/")
                || lower.contains("/layout/") || lower.contains("/common/");
        boolean genericName = GENERIC_COMPONENT_NAMES.contains(comp.toLowerCase());
        if (underShared && !hasApi && !hasInteraction) {
            score -= 0.8;                  // 共享目录且无业务行为 -> 通用
        } else if (genericName && !hasApi && !hasInteraction) {
            score -= 0.6;
        }
        return Math.max(-1.0, Math.min(1.0, score));
    }

    private String sanitizeId(String rel) {
        return rel.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private String relativize(File base, File file) {
        try {
            return base.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.getName();
        }
    }

    // 组件摘要 system prompt 常量——v7.5(A11) 缓存键与 LLM 调用必须共用同一字符串
    private static final String SUMMARY_SYSTEM_PROMPT = "你是前端代码分析助手，只输出合法 JSON。";

    // v6.1: 单组件 LLM 语义增强——完整读文件生成语义摘要，不截断源码。
    private void enhanceComponentSummary(File dir, Map<String, Object> m) {
        String file = String.valueOf(m.get("file"));
        File source = new File(dir, file);
        String content = readFile(source);
        if (content == null || content.isBlank()) {
            return;
        }
        String component = String.valueOf(m.get("component"));
        String path = String.valueOf(m.getOrDefault("route", ""));
        String prompt = """
                你是 Vue 组件语义分析专家。请完整阅读下面的组件源码（不截断），提炼一份端到端测试可用的语义摘要。
                输出纯 JSON，不要 markdown 代码块，结构：
                {
                  "summary": "一句话语义摘要（含业务场景）",
                  "interactions": ["用户操作/事件"],
                  "apiCalls": ["调用的接口 URL"],
                  "stateOps": ["状态/弹窗/表单控制变量"],
                  "routeNavigations": ["路由跳转目标"],
                  "keywords": ["关键词"]
                }
                组件：%s，路由：%s
                源码：
                %s
                只返回 JSON。""".formatted(component, path, content);

        // v7.5(A11): 先查缓存——组件源码未变直接复用摘要，不重复调 LLM
        String cached = llmResultCacheService.get("component_summary", SUMMARY_SYSTEM_PROMPT, prompt);
        if (cached != null) {
            if (mergeComponentSummary(cached, m)) {
                log.debug("Component summary cache hit: {}", component);
                return;
            }
            log.debug("Component summary cache parse failed, fallback to LLM: {}", component);
        }

        String response = llmService.chat(SUMMARY_SYSTEM_PROMPT, prompt, 0.2);
        // v7.5(A11): 解析成功才写缓存（解析失败的响应不缓存，防毒缓存）
        if (mergeComponentSummary(response, m)) {
            llmResultCacheService.put("component_summary", SUMMARY_SYSTEM_PROMPT, prompt, response);
        }
    }

    /**
     * v7.5(A11): 返回解析是否成功——成功才写缓存；命中缓存解析失败时调用方落回 LLM 路径。
     */
    private boolean mergeComponentSummary(String response, Map<String, Object> m) {
        String json = response == null ? "" : response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf("{");
            int end = json.lastIndexOf("}");
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (root.hasNonNull("summary")) {
                m.put("summary", root.path("summary").asText(""));
            }
            mergeStringArray(root.path("interactions"), "interactions", m);
            mergeStringArray(root.path("apiCalls"), "apiCalls", m);
            mergeStringArray(root.path("stateOps"), "stateOps", m);
            mergeStringArray(root.path("routeNavigations"), "routeNavigations", m);
            mergeStringArray(root.path("keywords"), "keywords", m);
            return true;
        } catch (Exception e) {
            log.debug("Failed to parse component summary for {}: {}", m.get("component"), e.getMessage());
            return false;
        }
    }

    private void mergeStringArray(JsonNode node, String key, Map<String, Object> m) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode n : node) {
            String v = n.asText("").trim();
            if (!v.isEmpty() && !values.contains(v)) {
                values.add(v);
            }
        }
        if (!values.isEmpty()) {
            m.put(key, values);
        }
    }

    private void enhanceWithLlm(List<Map<String, Object>> forms,
                                List<Map<String, Object>> componentStates,
                                List<Map<String, Object>> domSelectors,
                                List<Map<String, Object>> pageFlows,
                                File dir,
                                List<String> warnings) {
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
        parseAndMergeSupplements(response, forms, componentStates, domSelectors, pageFlows, warnings);
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
    // v7.4(A10): forms 改字段级合并、domSelectors 改选择器级合并——此前组件已存在即整条丢弃
    // LLM 补充（含正则漏掉的字段），多 form/部分提取场景字段级信息丢失
    @SuppressWarnings("unchecked")
    private void parseAndMergeSupplements(String response,
                                           List<Map<String, Object>> forms,
                                           List<Map<String, Object>> componentStates,
                                           List<Map<String, Object>> domSelectors,
                                           List<Map<String, Object>> pageFlows,
                                           List<String> warnings) {
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
            warnings.add("LLM 补充结果 JSON 解析失败（" + e.getMessage() + "），本次补充未合并");
            return;
        }

        // 合并 forms（v7.4(A10): 字段级合并——按 field name 去重，正则已有字段保留，LLM 新字段追加）
        if (root.has("supplementalForms") && root.get("supplementalForms").isArray()) {
            for (JsonNode node : root.get("supplementalForms")) {
                String component = node.path("component").asText("");
                if (component.isEmpty()) continue;
                Map<String, Object> existing = forms.stream()
                    .filter(f -> component.equals(f.get("component")))
                    .findFirst().orElse(null);
                List<Map<String, Object>> llmFields = parseFields(node.path("fields"));
                if (existing == null) {
                    Map<String, Object> form = new LinkedHashMap<>();
                    form.put("component", component);
                    form.put("fields", llmFields);
                    form.put("file", node.path("file").asText(""));
                    forms.add(form);
                    log.info("LLM supplemented form: {} ({} fields)", component, llmFields.size());
                } else {
                    List<Map<String, Object>> currentFields = existing.get("fields") instanceof List
                            ? (List<Map<String, Object>>) existing.get("fields")
                            : new ArrayList<>();
                    int added = 0;
                    for (Map<String, Object> lf : llmFields) {
                        Object name = lf.get("name");
                        boolean dup = name != null && currentFields.stream()
                                .anyMatch(f -> name.equals(f.get("name")));
                        if (!dup && name != null && !String.valueOf(name).isBlank()) {
                            currentFields.add(lf);
                            added++;
                        }
                    }
                    if (added > 0) {
                        existing.put("fields", currentFields);
                        log.info("LLM supplemented fields for {}: +{}", component, added);
                    }
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

        // 合并 domSelectors（v7.4(A10): 选择器级合并——按 type+value 去重追加，而非组件级整条丢弃）
        if (root.has("supplementalSelectors") && root.get("supplementalSelectors").isArray()) {
            for (JsonNode node : root.get("supplementalSelectors")) {
                String component = node.path("component").asText("");
                if (component.isEmpty()) continue;
                Map<String, Object> existing = domSelectors.stream()
                    .filter(s -> component.equals(s.get("component")))
                    .findFirst().orElse(null);
                List<Map<String, Object>> llmSelectors = parseSelectors(node.path("selectors"));
                if (existing == null) {
                    Map<String, Object> sel = new LinkedHashMap<>();
                    sel.put("component", component);
                    sel.put("selectors", llmSelectors);
                    sel.put("file", node.path("file").asText(""));
                    domSelectors.add(sel);
                    log.info("LLM supplemented selectors: {} ({} items)", component, llmSelectors.size());
                } else {
                    List<Map<String, Object>> current = existing.get("selectors") instanceof List
                            ? (List<Map<String, Object>>) existing.get("selectors")
                            : new ArrayList<>();
                    int added = 0;
                    for (Map<String, Object> ls : llmSelectors) {
                        String type = String.valueOf(ls.getOrDefault("type", ""));
                        String value = String.valueOf(ls.getOrDefault("value", ""));
                        boolean dup = current.stream().anyMatch(s ->
                                type.equals(String.valueOf(s.getOrDefault("type", "")))
                                        && value.equals(String.valueOf(s.getOrDefault("value", ""))));
                        if (!dup && !value.isBlank()) {
                            current.add(ls);
                            added++;
                        }
                    }
                    if (added > 0) {
                        existing.put("selectors", current);
                        log.info("LLM supplemented selectors for {}: +{}", component, added);
                    }
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
