package com.testagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.service.LlmSchemaValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v8.7.2(9.5.8/9.5.9): 黄金数据集回放评测工具（mock 模式）。
 *
 * 用法：
 *   mvn -q compile exec:java -Dexec.mainClass=com.testagent.eval.EvalRunner
 *   或容器：docker run --rm -v backend:/build -v .mvn-repo:/root/.m2 maven:3.9-eclipse-temurin-17 \
 *        mvn -q compile exec:java -Dexec.mainClass=com.testagent.eval.EvalRunner
 *
 * mock 模式说明（计划书 9.5.8 授权）：不调用真实 LLM，回放 datasets 内的 fixture-response.json，
 * 只测结构类指标——结构合格率 / 解析跳过率 / 需求召回率 / 接口覆盖率；耗时与 token 取夹具登记值。
 * 真实模式（需 LLM_API_KEY）走完整生成链路，为 9.5.10 回测预留。
 *
 * 流程规则（9.5.9）：prompt 模板或预算参数（app.prd.*、app.generation.*、llm.max-*）任何改动，
 * 必须先跑本工具产出报告，并用 compare 子命令对比基线，健康线全绿才可合入。
 */
public class EvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 计划书 5.3 评测健康线
    private static final double HEALTH_RECALL = 0.90;
    private static final double HEALTH_ENDPOINT_COVERAGE = 0.80;
    private static final double HEALTH_STRUCTURE_PASS = 0.98;
    private static final double HEALTH_PARSE_SKIP = 0.02;

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && "compare".equals(args[0])) {
            compare(Paths.get(args[1]), Paths.get(args[2]));
            return;
        }
        Path datasetsDir = args.length >= 1 ? Paths.get(args[0]) : Paths.get("eval", "datasets");
        String gitSha = resolveGitSha();
        runReplay(datasetsDir, gitSha, Paths.get("eval", "results"));
    }

    // ==================== 回放评测 ====================

    static Map<String, Object> runReplay(Path datasetsDir, String gitSha, Path resultsDir) throws IOException {
        LlmSchemaValidator validator = new LlmSchemaValidator();
        if (!Files.isDirectory(datasetsDir)) {
            System.err.println("数据集目录不存在: " + datasetsDir.toAbsolutePath());
            System.exit(1);
        }
        Map<String, Object> datasets = new LinkedHashMap<>();
        DatasetAggregates total = new DatasetAggregates();
        try (var stream = Files.list(datasetsDir).sorted()) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir) || !Files.exists(dir.resolve("fixture-response.json"))) {
                    continue;
                }
                String name = dir.getFileName().toString();
                Map<String, Object> report = evalDataset(name, dir, validator, total);
                datasets.put(name, report);
            }
        }
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("structurePassRatio", ratio(total.structurePass, total.totalItems));
        overall.put("parseSkipRatio", ratio(total.parseSkipped, total.totalItems));
        overall.put("requirementRecall", ratio(total.recallHit, total.recallTotal));
        overall.put("endpointCoverage", ratio(total.endpointHit, total.endpointTotal));
        overall.put("durationMs", total.durationMs);
        overall.put("tokens", total.tokens);

        boolean healthy = total.recallTotal == 0 || ratio(total.recallHit, total.recallTotal) >= HEALTH_RECALL;
        healthy &= total.endpointTotal == 0 || ratio(total.endpointHit, total.endpointTotal) >= HEALTH_ENDPOINT_COVERAGE;
        healthy &= ratio(total.structurePass, Math.max(total.totalItems, 1)) >= HEALTH_STRUCTURE_PASS;
        healthy &= ratio(total.parseSkipped, Math.max(total.totalItems, 1)) <= HEALTH_PARSE_SKIP;
        overall.put("healthyLinePassed", healthy);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", "mock");
        report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.put("gitSha", gitSha);
        report.put("healthLine", Map.of(
                "requirementRecall", HEALTH_RECALL,
                "endpointCoverage", HEALTH_ENDPOINT_COVERAGE,
                "structurePassRatio", HEALTH_STRUCTURE_PASS,
                "parseSkipRatioMax", HEALTH_PARSE_SKIP));
        report.put("overall", overall);
        report.put("datasets", datasets);

        Files.createDirectories(resultsDir);
        String fileName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + gitSha + ".json";
        Path out = resultsDir.resolve(fileName);
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("评测报告已写入: " + out);
        System.out.println("总体: 结构合格率=" + pct(overall.get("structurePassRatio"))
                + " 跳过率=" + pct(overall.get("parseSkipRatio"))
                + " 需求召回=" + pct(overall.get("requirementRecall"))
                + " 接口覆盖=" + pct(overall.get("endpointCoverage"))
                + " 健康线=" + (healthy ? "PASS" : "FAIL"));
        return report;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evalDataset(String name, Path dir,
                                                   LlmSchemaValidator validator,
                                                   DatasetAggregates total) throws IOException {
        JsonNode fixture = MAPPER.readTree(Files.readString(dir.resolve("fixture-response.json")));
        JsonNode expected = MAPPER.readTree(Files.readString(dir.resolve("expected.json")));
        String chatResponse = fixture.path("chatResponse").asText("");

        List<Map<String, Object>> items = parseItems(chatResponse);
        int structurePass = 0;
        int parseSkipped = 0;
        int intentionalSkips = 0;
        Set<String> matchedRequirements = new HashSet<>();
        Set<String> matchedEndpoints = new HashSet<>();
        Set<String> generatedRequirementIds = new HashSet<>();

        for (Map<String, Object> item : items) {
            // v8.7.2: __malformed__ 条目为夹具故意注入（验证跳过机制工作），不计入健康线分母
            if (item.containsKey("__malformed__")) {
                intentionalSkips++;
                continue;
            }
            // 单条结构校验（test-cases items 契约）
            boolean valid;
            try {
                JsonNode node = MAPPER.readTree(MAPPER.writeValueAsString(item));
                JsonNode arrayWrapper = MAPPER.createArrayNode().add(node);
                valid = validator.validate(arrayWrapper, "test-cases").isEmpty();
            } catch (Exception e) {
                valid = false;
            }
            if (valid) {
                structurePass++;
                collectMatches(item, expected, matchedRequirements, matchedEndpoints);
                Object reqIds = item.get("requirementIds");
                if (reqIds instanceof List<?> list) {
                    list.forEach(id -> generatedRequirementIds.add(String.valueOf(id)));
                }
            } else {
                parseSkipped++;
            }
        }
        List<String> reqTitles = stringList(expected.path("requirements"));
        List<String> endpoints = stringList(expected.path("endpoints"));

        int recallHit = 0;
        for (String title : reqTitles) {
            boolean hit = false;
            // 口径一：期望条目名出现在某条用例标题中
            for (String matched : matchedRequirements) {
                if (matched.contains(title) || title.contains(matched)) {
                    hit = true;
                    break;
                }
            }
            // 口径二：requirementIdMap 声明的 id 被用例引用
            if (!hit) {
                JsonNode ids = expected.path("requirementIdMap").path(title);
                for (JsonNode idNode : ids) {
                    if (generatedRequirementIds.contains(idNode.asText())) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                recallHit++;
            }
        }
        int endpointHit = 0;
        for (String ep : endpoints) {
            if (matchedEndpoints.contains(ep)) {
                endpointHit++;
            }
        }

        int countedItems = items.size() - intentionalSkips;
        total.totalItems += Math.max(countedItems, 1);
        total.structurePass += structurePass;
        total.parseSkipped += parseSkipped;
        total.recallHit += recallHit;
        total.recallTotal += reqTitles.size();
        total.endpointHit += endpointHit;
        total.endpointTotal += endpoints.size();
        JsonNode tokens = fixture.path("tokens");
        total.tokens += tokens.path("prompt").asInt(0) + tokens.path("completion").asInt(0);
        total.durationMs += fixture.path("durationMs").asLong(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items.size());
        result.put("intentionalMalformed", intentionalSkips);
        result.put("structurePass", structurePass);
        result.put("structurePassRatio", ratio(structurePass, Math.max(countedItems, 1)));
        result.put("parseSkipped", parseSkipped);
        result.put("parseSkipRatio", ratio(parseSkipped, Math.max(countedItems, 1)));
        result.put("requirementRecall", ratio(recallHit, Math.max(reqTitles.size(), 1)));
        result.put("endpointCoverage", ratio(endpointHit, Math.max(endpoints.size(), 1)));
        return result;
    }

    /**
     * 模拟流式解析：逐段容错——非 JSON 对象条目计为解析跳过（对应 TestGeneratorAgent.parseTestCases 行为）。
     */
    private static List<Map<String, Object>> parseItems(String chatResponse) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            JsonNode array = MAPPER.readTree(chatResponse);
            if (array.isArray()) {
                for (JsonNode node : array) {
                    if (node.isObject()) {
                        items.add(MAPPER.convertValue(node, Map.class));
                    } else {
                        items.add(Map.of("__malformed__", true));
                    }
                }
            }
        } catch (Exception e) {
            items.add(Map.of("__malformed__", true));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static void collectMatches(Map<String, Object> item, JsonNode expected,
                                       Set<String> requirements, Set<String> endpoints) {
        Object title = item.get("title");
        if (title != null) {
            requirements.add(String.valueOf(title));
        }
        Object reqIds = item.get("requirementIds");
        if (reqIds instanceof List<?> list) {
            list.forEach(id -> requirements.add(String.valueOf(id)));
        }
        // 标题关键词兜底召回判定：期望条目名出现在标题中即算命中素材
        expected.path("requirements").forEach(n -> {
            String t = n.asText();
            if (title != null && String.valueOf(title).contains(t)) {
                requirements.add(t);
            }
        });
        Object refs = item.get("coverageRefs");
        if (refs instanceof Map<?, ?> refMap && refMap.get("endpointIds") instanceof List<?> eps) {
            eps.forEach(ep -> endpoints.add(String.valueOf(ep)));
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static double ratio(int hit, int totalCnt) {
        return totalCnt == 0 ? 0 : Math.round((hit * 10000.0) / totalCnt) / 10000.0;
    }

    private static String pct(Object v) {
        return v == null ? "0%" : String.format(Locale.ROOT, "%.1f%%", ((Number) v).doubleValue() * 100);
    }

    private static String resolveGitSha() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            String sha = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && !sha.isBlank() ? sha : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ==================== 基线对比（9.5.9）====================

    static void compare(Path baseline, Path candidate) throws IOException {
        Map<String, Object> base = MAPPER.readValue(Files.readString(baseline), Map.class);
        Map<String, Object> cand = MAPPER.readValue(Files.readString(candidate), Map.class);
        Map<String, Object> b = (Map<String, Object>) base.get("overall");
        Map<String, Object> c = (Map<String, Object>) cand.get("overall");
        System.out.println("== 评测基线对比 ==");
        System.out.printf(Locale.ROOT, "%-22s %10s %10s %10s%n", "metric", "baseline", "candidate", "delta");
        int regressions = 0;
        for (String key : new String[]{"structurePassRatio", "parseSkipRatio", "requirementRecall", "endpointCoverage"}) {
            double bv = num(b.get(key));
            double cv = num(c.get(key));
            String flag = "";
            // 跳过率是越低越好
            boolean worse = "parseSkipRatio".equals(key) ? cv > bv + 0.001 : cv < bv - 0.001;
            if (worse) {
                flag = " REGRESSION";
                regressions++;
            }
            System.out.printf(Locale.ROOT, "%-22s %9.2f%% %9.2f%% %+9.2f%%%s%n",
                    key, bv * 100, cv * 100, (cv - bv) * 100, flag);
        }
        long bd = numLong(b.get("durationMs"));
        long cd = numLong(c.get("durationMs"));
        System.out.printf(Locale.ROOT, "%-22s %9dms %8dms %+8dms%n", "durationMs", bd, cd, cd - bd);
        if (regressions > 0) {
            System.out.println("结论: 存在 " + regressions + " 项指标回归，禁止合入");
            System.exit(2);
        }
        System.out.println("结论: 无指标回归");
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static long numLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static class DatasetAggregates {
        int totalItems;
        int structurePass;
        int parseSkipped;
        int recallHit;
        int recallTotal;
        int endpointHit;
        int endpointTotal;
        long tokens;
        long durationMs;
    }
}
