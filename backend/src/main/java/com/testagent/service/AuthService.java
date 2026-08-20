package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.dto.LoginRequest;
import com.testagent.dto.RegisterRequest;
import com.testagent.dto.UserDTO;
import com.testagent.entity.User;
import com.testagent.repository.UserRepository;
import com.testagent.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v4.0: 注册/登录/当前用户。
 */
@Service
public class AuthService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Transactional
    public Map<String, Object> register(RegisterRequest req) {
        String username = req.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(40101, "用户名已存在", HttpStatus.BAD_REQUEST);
        }
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setDisplayName(req.getDisplayName() == null || req.getDisplayName().isBlank()
                ? username : req.getDisplayName().trim());
        user.setRole("USER");
        user.setMustChangePassword(false);
        userRepository.save(user);
        return buildAuthResponse(user);
    }

    public Map<String, Object> login(LoginRequest req) {
        String username = req.getUsername().trim();
        loginAttemptService.checkLocked(username);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(40102, "用户名或密码错误", HttpStatus.UNAUTHORIZED));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            loginAttemptService.loginFailed(username);
            throw new BusinessException(40102, "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        loginAttemptService.loginSucceeded(username);
        return buildAuthResponse(user);
    }

    // v4.1: 修改密码
    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        if (username == null) {
            throw new BusinessException(40100, "未登录", HttpStatus.UNAUTHORIZED);
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(40103, "用户不存在", HttpStatus.UNAUTHORIZED));
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(40105, "原密码错误", HttpStatus.BAD_REQUEST);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // v6.6: 修改密码后清除“强制改密”标记
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    public UserDTO me(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(40103, "用户不存在", HttpStatus.UNAUTHORIZED));
        return UserDTO.from(user);
    }

    private Map<String, Object> buildAuthResponse(User user) {
        String token = jwtUtil.generateToken(user.getUsername(), user.getRole());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", UserDTO.from(user));
        return result;
    }
}
