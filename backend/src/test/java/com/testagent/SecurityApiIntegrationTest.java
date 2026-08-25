package com.testagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * vT6: API 层安全与权限集成测试（H2 + 内存运行态，禁用 MCP）。
 * 需要 LLM API Key 才能启动完整上下文，按仓库约定用 *IntegrationTest 命名，
 * 由 surefire 排除，避免 CI（无 LLM Key）下上下文启动失败。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "app.mcp.enabled=false",
        "app.milvus.enabled=false",
        "app.redis.enabled=false",
        // v8.5: SecurityKeyGuard 全 profile 必填，测试上下文显式补键；桥接 token 固定供断言
        "app.jwt.secret=integration-test-jwt-secret-0123456789abcdef",
        "app.admin.password=integration-test-admin-pw",
        "app.milvus.password=integration-test-milvus-pw",
        "app.mcp.bridge-token=test-mcp-token-2026"
})
@AutoConfigureMockMvc
class SecurityApiIntegrationTest {

    private static final String MCP_TOKEN = "test-mcp-token-2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String loginToken(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "passw0rd123",
                                "displayName", "Tester"))))
                .andExpect(status().isOk());
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "passw0rd123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(loginResponse).path("data").path("token").asText();
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void settingsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminDataHealthRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/data/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settingsAreAdminOnly() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "tester",
                                "password", "passw0rd123",
                                "displayName", "Tester"))))
                .andExpect(status().isOk());

        String loginBody = objectMapper.writeValueAsString(Map.of(
                "username", "tester",
                "password", "passw0rd123"));
        String loginResponse = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginResponse).path("data").path("token").asText();

        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginLockedAfterFiveFailures() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "admin",
                "password", "wrong-password"));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    // ========== v8.5: 安防能力回归固化（任务 8.6）==========

    private static final String MCP_BODY = "{\"projectId\":\"p\",\"query\":\"q\"}";

    @Test
    void mcpWithoutTokenRejected() throws Exception {
        // v8.5: 无 token → 401
        mockMvc.perform(post("/api/mcp/semantic-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void mcpWithWrongTokenRejected() throws Exception {
        // v8.5: 错误 token → 401
        mockMvc.perform(post("/api/mcp/semantic-search")
                        .header("X-MCP-Token", "totally-wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void mcpFromNonLoopbackForbiddenEvenWithCorrectToken() throws Exception {
        // v8.5: 非回环来源 + 正确 token → 403（来源校验先于 token）
        mockMvc.perform(post("/api/mcp/semantic-search")
                        .header("X-MCP-Token", MCP_TOKEN)
                        .with(req -> {
                            req.setRemoteAddr("10.9.9.9");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void mcpLoopbackWithCorrectTokenAccepted() throws Exception {
        // v8.5: 回环 + 正确 token 通过（semanticSearch 对不存在项目返回空列表而非报错）
        mockMvc.perform(post("/api/mcp/semantic-search")
                        .header("X-MCP-Token", MCP_TOKEN)
                        .with(req -> {
                            req.setRemoteAddr("127.0.0.1");
                            return req;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MCP_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void filesystemOutsideBrowseRootsRejected() throws Exception {
        // v8.5: 白名单外绝对路径拒绝，且错误信息不泄漏服务器绝对路径
        String body = mockMvc.perform(get("/api/filesystem/dirs")
                        .header("Authorization", "Bearer " + loginToken("fs-outside"))
                        .param("path", "/etc"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("非法路径"))
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                body.replace("\\\\", "\\").contains(":\\"), "响应不得泄漏绝对路径: " + body);
    }

    @Test
    void filesystemRelativeEscapeRejected() throws Exception {
        // v8.5: 相对路径 .. 逃逸白名单同样拒绝
        mockMvc.perform(get("/api/filesystem/dirs")
                        .header("Authorization", "Bearer " + loginToken("fs-escape"))
                        .param("path", "projects/../escape"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("非法路径"));
    }

    @Test
    void mcpAnalyzeBackendSourcePathEscapeRejected() throws Exception {
        // v8.5: sourcePath 白名单逃逸（../）→ 40300
        mockMvc.perform(post("/api/mcp/analyze-backend")
                        .header("X-MCP-Token", MCP_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourcePath\":\"../../outside\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
