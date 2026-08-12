package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.LoginRequest;
import com.testagent.dto.RegisterRequest;
import com.testagent.dto.UserDTO;
import com.testagent.security.SecurityUtils;
import com.testagent.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * v4.0: 认证接口。
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    @Autowired
    private AuthService authService;

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.success(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req));
    }

    @GetMapping("/me")
    public ApiResponse<UserDTO> me() {
        return ApiResponse.success(authService.me(SecurityUtils.currentUsername()));
    }
}
