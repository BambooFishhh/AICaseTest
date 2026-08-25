package com.testagent.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.agent.PrdAgent;
import com.testagent.agent.StateMachineAgent;
import com.testagent.agent.TestCaseReviewAgent;
import com.testagent.analyzer.SpringAnalyzer;
import com.testagent.analyzer.VueAnalyzer;
import com.testagent.analyzer.result.BackendResult;
import com.testagent.analyzer.result.FrontendResult;
import com.testagent.common.ApiResponse;
import com.testagent.common.BusinessException;
import com.testagent.dto.PrdAnalysisResult;
import com.testagent.entity.StateMachine;
import com.testagent.entity.TestCase;
import com.testagent.service.SemanticService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v5.13: MCP 桥接接口。
 * tools-mcp-server 通过 HTTP 调用这些接口，复用现有 Service/Agent 能力。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpBridgeController {

    private static final Logger log = LoggerFactory.getLogger(McpBridgeController.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SemanticService semanticService;

    @Autowired
    private PrdAgent prdAgent;

    @Autowired
    private StateMachineAgent stateMachineAgent;

    @Autowired
    private TestCaseReviewAgent testCaseReviewAgent;

    @Autowired
    private SpringAnalyzer springAnalyzer;

    @Autowired
    private VueAnalyzer vueAnalyzer;

    @Value("${app.mcp.bridge-token:aicasetest-mcp-local}")
    private String bridgeToken;

    // v8.5: /api/mcp/** 来源白名单——默认仅回环，反代部署可经 APP_MCP_ALLOWED_REMOTE_ADDRS 注入额外 IP
    @Value("${app.mcp.allowed-remote-addrs:}")
    private List<String> allowedRemoteAddrs = new ArrayList<>();

    // v8.4fix: 分析接口只允许扫描项目目录与 Git 克隆目录，杜绝任意本地目录读取
    @Value("${app.projects-dir:projects}")
    private String projectsDir;

    @Value("${app.git.clone-dir:data/git-repos}")
    private String gitCloneDir;

    @PostMapping("/semantic-search")
    public ApiResponse<List<String>> semanticSearch(@RequestBody Map<String, Object> body,
                                                     HttpServletRequest request) {
        assertBridgeToken(request);
        String projectId = text(body, "projectId");
        String query = text(body, "query");
        if (projectId.isEmpty() || query.isEmpty()) {
            throw BusinessException.invalidParam("projectId 和 query 不能为空");
        }
        int topK = intValue(body.get("topK"), 5);
        return ApiResponse.success(semanticService.retrieveContexts(projectId, query, topK));
    }

    @PostMapping("/analyze-requirement-docs")
    public ApiResponse<PrdAnalysisResult> analyzeRequirementDocs(@RequestBody Map<String, Object> body,
                                                                 HttpServletRequest request) {
        assertBridgeToken(request);
        List<Map<String, Object>> prdDocs = mapList(body.get("prdDocs"));
        List<Map<String, Object>> contextDocs = mapList(body.get("contextDocs"));
        String supplementary = text(body, "supplementary");
        return ApiResponse.success(prdAgent.analyze(prdDocs, contextDocs, supplementary));
    }

    @PostMapping("/extract-state-machine")
    public ApiResponse<List<StateMachine>> extractStateMachine(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest request) {
        assertBridgeToken(request);
        BackendResult backendResult = objectMapper.convertValue(body.get("backendResult"), BackendResult.class);
        FrontendResult frontendResult = objectMapper.convertValue(body.get("frontendResult"), FrontendResult.class);
        if (backendResult == null) {
            throw BusinessException.invalidParam("backendResult 不能为空");
        }
        return ApiResponse.success(stateMachineAgent.extract(backendResult, frontendResult));
    }

    @PostMapping("/review-test-cases")
    public ApiResponse<List<TestCase>> reviewTestCases(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        assertBridgeToken(request);
        List<TestCase> cases = objectMapper.convertValue(body.get("cases"), new TypeReference<>() {});
        Map<String, Object> coverage = objectMapper.convertValue(body.get("coverage"), new TypeReference<>() {});
        return ApiResponse.success(testCaseReviewAgent.review(cases, coverage));
    }

    @PostMapping("/analyze-backend")
    public ApiResponse<BackendResult> analyzeBackend(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        assertBridgeToken(request);
        String sourcePath = assertAllowedSourcePath(text(body, "sourcePath"));
        return ApiResponse.success(springAnalyzer.analyze(sourcePath));
    }

    @PostMapping("/analyze-frontend")
    public ApiResponse<FrontendResult> analyzeFrontend(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        assertBridgeToken(request);
        String sourcePath = assertAllowedSourcePath(text(body, "sourcePath"));
        return ApiResponse.success(vueAnalyzer.analyze(sourcePath));
    }

    // v8.4fix: 规范化后必须落在项目目录/克隆目录白名单内，拒绝任意路径（含 ..、绝对路径越权）
    private String assertAllowedSourcePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            throw BusinessException.invalidParam("sourcePath 不能为空");
        }
        Path target = Paths.get(sourcePath).toAbsolutePath().normalize();
        for (String root : new String[]{projectsDir, gitCloneDir}) {
            Path rootPath = Paths.get(root).toAbsolutePath().normalize();
            if (target.startsWith(rootPath)) {
                return target.toString();
            }
        }
        throw new BusinessException(40300, "sourcePath 不在允许扫描的目录范围内", HttpStatus.FORBIDDEN);
    }

    private void assertBridgeToken(HttpServletRequest request) {
        // v8.5: 来源校验先于 token——非回环即使携带正确 token 也返回 403，token 降为第二因子
        assertLoopbackSource(request);
        String token = request.getHeader("X-MCP-Token");
        String supplied = token == null ? "" : token;
        // v8.4fix: 常量时间比较，避免时序侧信道；默认空/弱 token 直接拒绝（生产由 ProductionGuard 拦截）
        boolean valid = bridgeToken != null && !bridgeToken.isBlank()
                && MessageDigest.isEqual(
                        bridgeToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!valid) {
            throw new BusinessException(40100, "MCP bridge 未授权", HttpStatus.UNAUTHORIZED);
        }
    }

    // v8.5: 仅接受回环来源（127.*、::1），杜绝外部主机直连桥接接口；反代场景用白名单显式放行
    private void assertLoopbackSource(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip != null && (isLoopbackIp(ip) || allowedRemoteAddrs.contains(ip))) {
            return;
        }
        log.warn("拒绝非回环来源的 MCP 桥接请求: remoteAddr={}", ip);
        throw new BusinessException(40300, "MCP bridge 仅允许本机回环访问", HttpStatus.FORBIDDEN);
    }

    private boolean isLoopbackIp(String ip) {
        return ip.equals("127.0.0.1") || ip.startsWith("127.")
                || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
