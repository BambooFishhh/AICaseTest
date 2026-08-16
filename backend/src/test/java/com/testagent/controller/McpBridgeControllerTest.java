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
}
