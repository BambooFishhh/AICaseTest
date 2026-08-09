package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.dto.SettingsDTO;
import com.testagent.dto.UpdateSettingsRequest;
import com.testagent.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin
public class SettingsController {

    @Autowired
    private SettingsService settingsService;

    @GetMapping
    public ApiResponse<SettingsDTO> getSettings() {
        return ApiResponse.success(settingsService.getSettings());
    }

    @PutMapping
    public ApiResponse<Void> updateSettings(@RequestBody UpdateSettingsRequest req) {
        settingsService.updateSettings(req);
        return ApiResponse.success(null, "设置已更新");
    }

    @PostMapping("/test-llm")
    public ApiResponse<Map<String, Object>> testLlm() {
        return ApiResponse.success(settingsService.testLlm());
    }
}
