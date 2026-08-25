package com.testagent.controller;

import com.testagent.common.BusinessException;
import com.testagent.service.SemanticService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpBridgeControllerTest {

    @Test
    void missingTokenIsRejected() {
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request));

        assertEquals(40100, ex.getCode());
    }

    @Test
    void semanticSearchWithTokenReturnsContexts() {
        SemanticService semanticService = mock(SemanticService.class);
        when(semanticService.retrieveContexts("p", "q", 5)).thenReturn(List.of("ctx1"));
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "semanticService", semanticService);
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-MCP-Token", "secret");

        var response = controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request);

        assertEquals(List.of("ctx1"), response.getData());
    }

    // v8.5: 回环 + 错误 token → 401
    @Test
    void wrongTokenIsRejectedEvenFromLoopback() {
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-MCP-Token", "wrong");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request));

        assertEquals(40100, ex.getCode());
    }

    // v8.5: 非回环来源即使携带正确 token 也返回 403——来源校验先于 token
    @Test
    void nonLoopbackSourceForbiddenEvenWithCorrectToken() {
        SemanticService semanticService = mock(SemanticService.class);
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "semanticService", semanticService);
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.9.9.9");
        request.addHeader("X-MCP-Token", "secret");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request));

        assertEquals(40300, ex.getCode());
    }

    // v8.5: 反代场景白名单放行——非回环但命中 allowedRemoteAddrs 的来源可访问
    @Test
    void whitelistedRemoteAddrAccepted() {
        SemanticService semanticService = mock(SemanticService.class);
        when(semanticService.retrieveContexts("p", "q", 5)).thenReturn(List.of("ctx1"));
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "semanticService", semanticService);
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        ReflectionTestUtils.setField(controller, "allowedRemoteAddrs", List.of("10.0.0.2"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-MCP-Token", "secret");

        var response = controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request);

        assertEquals(List.of("ctx1"), response.getData());
    }

    // v8.5: IPv6 回环形式放行
    @Test
    void ipv6LoopbackAcceptedWithToken() {
        SemanticService semanticService = mock(SemanticService.class);
        when(semanticService.retrieveContexts("p", "q", 5)).thenReturn(List.of("ctx1"));
        McpBridgeController controller = new McpBridgeController();
        ReflectionTestUtils.setField(controller, "semanticService", semanticService);
        ReflectionTestUtils.setField(controller, "bridgeToken", "secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("0:0:0:0:0:0:0:1");
        request.addHeader("X-MCP-Token", "secret");

        var response = controller.semanticSearch(Map.of("projectId", "p", "query", "q"), request);

        assertEquals(List.of("ctx1"), response.getData());
    }
}
