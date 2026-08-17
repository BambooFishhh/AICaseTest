package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.service.LlmService;
import com.testagent.service.PromptSkillLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// v1.10: PRD 解析 Agent，把 PRD 文本解析为结构化 PrdAnalysisResult
@Component
public class PrdAgent {

    private static final Logger log = LoggerFactory.getLogger(PrdAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    @Autowired
    private PromptSkillLoader promptSkillLoader;

    // v1.10: PRD 文本最大长度（防止 LLM token 超限）
    private static final int MAX_PRD_LENGTH = 12000;
    private static final int MAX_TOTAL_DOC_LENGTH = 24000;
    private static final int MAX_FETCH_BYTES = 2_000_000;
    private static final int FETCH_TIMEOUT_MS = 10_000;
    private static final int MAX_REDIRECTS = 3;

    /**
     * 解析 PRD 文本为结构化结果。
     * 失败时返回空结果（不阻断生成，由 TestGeneratorAgent 退化为代码驱动）。
     */
    public PrdAnalysisResult analyze(String prdContent) {
        if (prdContent == null || prdContent.isBlank()) {
            return new PrdAnalysisResult();
        }
        Map<String, Object> prdDoc = new LinkedHashMap<>();
        prdDoc.put("title", "主 PRD");
        prdDoc.put("content", prdContent);
        prdDoc.put("docType", "prd");
        return analyze(List.of(prdDoc), List.of(), null);
    }

    /**
     * v5.11: 多篇需求文档解析。输入明确区分 PRD 文档 / 上下文文档 / 补充需求，
     * 全部拼入 Prompt 后交给 LLM 做结构化需求分析。
     * v5.13fix: 解析失败或结果为空时抛出携带原始原因的 BusinessException，避免前端只见通用错误。
     */
    public PrdAnalysisResult analyze(List<Map<String, Object>> prdDocs,
                                     List<Map<String, Object>> contextDocs,
                                     String supplementary) {
        String requirementText = buildRequirementPrompt(prdDocs, contextDocs, supplementary);
        if (requirementText.isBlank()) {
            throw new BusinessException(50015, "PRD 解析失败：需求文档内容为空，请补充有效需求资料",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        try {
            PrdAnalysisResult result = analyzeByLlm(requirementText);
            if (result == null || result.isEmpty()) {
                throw new BusinessException(50015,
                        "PRD 解析失败：模型未提取到有效需求，请检查需求文档内容是否完整",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("PRD analyze failed: {}", e.getMessage(), e);
            throw new BusinessException(50015, "PRD 解析失败: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private String buildRequirementPrompt(List<Map<String, Object>> prdDocs,
                                          List<Map<String, Object>> contextDocs,
                                          String supplementary) {
        StringBuilder sb = new StringBuilder();
        int prdIndex = 0;
        if (prdDocs != null) {
            for (Map<String, Object> doc : prdDocs) {
                Object contentRaw = doc == null ? null : doc.get("content");
                String content = contentRaw instanceof String s
                        ? s : (contentRaw == null ? "" : String.valueOf(contentRaw));
                if (content.isBlank()) continue;
                prdIndex++;
                Object titleRaw = doc == null ? null : doc.get("title");
                String title = titleRaw instanceof String s
                        ? s : (titleRaw == null ? "" : String.valueOf(titleRaw));
                sb.append("## 【PRD 文档 ").append(prdIndex).append("】")
                        .append(title.isBlank() ? "" : " - " + title)
                        .append("\n").append(truncateDoc(content)).append("\n\n");
            }
        }
        int ctxIndex = 0;
        if (contextDocs != null) {
            for (Map<String, Object> doc : contextDocs) {
                Object contentRaw = doc == null ? null : doc.get("content");
                String content = contentRaw instanceof String s
                        ? s : (contentRaw == null ? "" : String.valueOf(contentRaw));
                if (content.isBlank()) continue;
                ctxIndex++;
                Object titleRaw = doc == null ? null : doc.get("title");
                String title = titleRaw instanceof String s
                        ? s : (titleRaw == null ? "" : String.valueOf(titleRaw));
                sb.append("## 【上下文文档 ").append(ctxIndex).append("】")
                        .append(title.isBlank() ? "" : " - " + title)
                        .append("\n").append(truncateDoc(content)).append("\n\n");
            }
        }
        if (supplementary != null && !supplementary.isBlank()) {
            sb.append("## 【补充需求】\n").append(truncateDoc(supplementary)).append("\n\n");
        }
        if (sb.length() > MAX_TOTAL_DOC_LENGTH) {
            sb.setLength(MAX_TOTAL_DOC_LENGTH);
            sb.append("\n...(需求资料已截断)");
        }
        return sb.toString();
    }

    private String truncateDoc(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.length() <= MAX_PRD_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_PRD_LENGTH) + "\n...(文档已截断)";
    }

    private PrdAnalysisResult analyzeByLlm(String requirementText) throws Exception {
        log.info("[PRD] 开始 LLM 解析, 需求资料长度={}", requirementText.length());
        String systemPrompt = promptSkillLoader.load("prd-analysis", """
                你是需求分析专家。输入包含三类资料，必须区分对待：
                - 【PRD 文档】是核心需求来源，作为模块/需求/验收标准的主要依据；
                - 【上下文文档】是补充业务说明、接口文档、约束条件等辅助资料；
                - 【补充需求】是用户额外要求，优先级高于一般上下文，用于修正或补充 PRD。
                把三类资料合并解析为结构化 JSON。
                返回 JSON：
                {
                  "modules": [{"name":"模块名","description":"描述"}],
                  "requirements": [{"title":"需求标题","description":"描述","acceptanceCriteria":["验收1"],"priority":"P0"}],
                  "businessRules": [{"rule":"规则描述","ruleType":"validation"}],
                  "stateFlows": [{"name":"状态机名","states":["状态1"],"transitions":[{"from":"","to":"","trigger":""}]}],
                  "entities": ["订单","用户"]
                }
                priority 取值：P0/P1/P2；ruleType 取值：validation/constraint/workflow。
                只返回 JSON，不要其他文字。
                """);
        String userPrompt = "需求资料：\n" + requirementText;
        log.info("[PRD] 调用 LlmService.chat() ...");
        long start = System.currentTimeMillis();
        String response = llmService.chatWithAnalysis(systemPrompt, userPrompt, 0.2);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[PRD] LLM 返回, 耗时={}ms, 响应长度={}", elapsed, response == null ? 0 : response.length());
        String json = extractJsonObject(response);
        PrdAnalysisResult result = objectMapper.readValue(json, PrdAnalysisResult.class);
        log.info("PRD analyzed: modules={}, requirements={}, rules={}, flows={}",
                result.getModules() == null ? 0 : result.getModules().size(),
                result.getRequirements() == null ? 0 : result.getRequirements().size(),
                result.getBusinessRules() == null ? 0 : result.getBusinessRules().size(),
                result.getStateFlows() == null ? 0 : result.getStateFlows().size());
        return result;
    }

    /**
     * v1.10: 解析 PDF 为文本（PDFBox）。
     * 扫描件/图片型 PDF 会返回空文本，调用方据此提示用户。
     */
    public String parsePdf(MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("PDF 文件为空");
        }
        try (InputStream is = file.getInputStream();
             PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("PDF 无可提取文本（可能是扫描件）");
            }
            return text.trim();
        }
    }

    /**
     * v1.10: 抓取公开 URL 正文（Jsoup）。
     * vP1: 仅允许 http/https 公开地址，禁止内网/回环/链路本地地址，限制大小/超时/重定向。
     * SPA / 需认证页面会拿到空内容。
     */
    public String fetchUrl(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 为空");
        }
        String current = validatePublicUrl(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Connection.Response response = Jsoup.connect(current)
                    .userAgent("Mozilla/5.0 (compatible; TestAgentBot/1.0)")
                    .timeout(FETCH_TIMEOUT_MS)
                    .maxBodySize(MAX_FETCH_BYTES)
                    .followRedirects(false)
                    .execute();
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new IllegalStateException("重定向缺少 Location: " + status);
                }
                current = validatePublicUrl(new URL(current).toURI().resolve(location).toString());
                continue;
            }
            if (status >= 400) {
                throw new IllegalStateException("URL 返回 HTTP " + status);
            }
            String text = response.parse().text();
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("URL 内容为空（可能是 SPA 或需认证）");
            }
            return text;
        }
        throw new IllegalStateException("重定向次数超过上限");
    }

    private String validatePublicUrl(String raw) throws Exception {
        URI uri = URI.create(raw);
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持 http/https URL");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机");
        }
        for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
            if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) {
                throw new IllegalArgumentException("禁止访问内网/私网地址: " + uri.getHost());
            }
        }
        return uri.toString();
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        String trimmed = text.trim();
        if (trimmed.contains("```")) {
            int fenceStart = trimmed.indexOf("```");
            int contentStart = trimmed.indexOf("\n", fenceStart);
            if (contentStart != -1) {
                int fenceEnd = trimmed.indexOf("```", contentStart);
                if (fenceEnd != -1) {
                    return trimmed.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }
        int first = trimmed.indexOf('{');
        int last = trimmed.lastIndexOf('}');
        if (first != -1 && last != -1 && last > first) {
            return trimmed.substring(first, last + 1);
        }
        return "{}";
    }
}
