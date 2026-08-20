package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.security.SecurityUtils;
import com.testagent.security.SseTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v6.6: SSE 短期票据接口。客户端先以 Authorization: Bearer 调用本接口换取短 TTL
 * ticket，再用 ?ticket= 建立 EventSource 连接，避免长期 JWT 出现在 URL。
 */
@RestController
@RequestMapping("/api/sse")
@CrossOrigin
public class SseTicketController {

    @Autowired
    private SseTicketService sseTicketService;

    @PostMapping("/ticket")
    public ApiResponse<Map<String, Object>> createTicket() {
        String username = SecurityUtils.currentUsername();
        String role = SecurityUtils.currentRole();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket", sseTicketService.issue(username, role));
        return ApiResponse.success(data);
    }
}
