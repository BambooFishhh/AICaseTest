package com.testagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.service.LlmService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;

// v1.10: PRD 解析 Agent，把 PRD 文本解析为结构化 PrdAnalysisResult
@Component
public class PrdAgent {

    private static final Logger log = LoggerFactory.getLogger(PrdAgent.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LlmService llmService;

    // v1.10: PRD 文本最大长度（防止 LLM token 超限）
    private static final int MAX_PRD_LENGTH = 12000;
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
        String truncated = prdContent.length() > MAX_PRD_LENGTH
                ? prdContent.substring(0, MAX_PRD_LENGTH) + "\n...(PRD已截断)"
                : prdContent;
        try {
            return analyzeByLlm(truncated);
        } catch (Exception e) {
            log.warn("PRD analyze failed, return empty: {}", e.getMessage());
            return new PrdAnalysisResult();
        }
    }

    private PrdAnalysisResult analyzeByLlm(String prdContent) throws Exception {
        log.info("[PRD] 开始 LLM 解析, PRD 长度={}", prdContent.length());
        String systemPrompt = """
                你是需求分析专家。把 PRD 文档解析为结构化 JSON。
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
                """;
        String userPrompt = "PRD 文档：\n" + prdContent;
        log.info("[PRD] 调用 LlmService.chat() ...");
        long start = System.currentTimeMillis();
        String response = llmService.chat(systemPrompt, userPrompt, 0.2);
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
