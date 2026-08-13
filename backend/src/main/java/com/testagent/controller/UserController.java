package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.entity.User;
import com.testagent.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.3: 用户查询（成员候选）。
 */
@RestController
@RequestMapping("/api/users")
@CrossOrigin
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return ApiResponse.success(userRepository.findAll().stream()
                .filter(u -> kw.isEmpty()
                        || u.getUsername().toLowerCase().contains(kw)
                        || (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(kw)))
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("displayName", u.getDisplayName());
                    m.put("role", u.getRole());
                    return m;
                })
                .toList());
    }
}
