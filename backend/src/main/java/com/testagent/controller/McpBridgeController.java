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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        String sourcePath = text(body, "sourcePath");
        if (sourcePath.isEmpty()) {
            throw BusinessException.invalidParam("sourcePath 不能为空");
        }
        return ApiResponse.success(springAnalyzer.analyze(sourcePath));
    }

    @PostMapping("/analyze-frontend")
    public ApiResponse<FrontendResult> analyzeFrontend(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        assertBridgeToken(request);
        String sourcePath = text(body, "sourcePath");
        if (sourcePath.isEmpty()) {
            throw BusinessException.invalidParam("sourcePath 不能为空");
        }
        return ApiResponse.success(vueAnalyzer.analyze(sourcePath));
    }

    private void assertBridgeToken(HttpServletRequest request) {
        String token = request.getHeader("X-MCP-Token");
        if (bridgeToken == null || bridgeToken.isBlank() || !bridgeToken.equals(token)) {
            throw new BusinessException(40100, "MCP bridge 未授权", HttpStatus.UNAUTHORIZED);
        }
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
