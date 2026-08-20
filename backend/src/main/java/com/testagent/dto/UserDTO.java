package com.testagent.dto;

import com.testagent.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserDTO {

    private String id;
    private String username;
    private String displayName;
    private String role;
    private Boolean mustChangePassword;
    private LocalDateTime createdAt;

    public static UserDTO from(User user) {
        if (user == null) return null;
        return UserDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .createdAt(user.getCreatedAt())
                .build();
    }
}
