package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.entity.Project;
import com.testagent.entity.User;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.UserRepository;
import com.testagent.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * v4.0: 项目级数据归属校验。
 */
@Service
public class ProjectAccessService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    public void assertProjectAccess(String projectId) {
        if (SecurityUtils.isAdmin()) {
            return;
        }
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            throw new BusinessException(40100, "未登录", HttpStatus.UNAUTHORIZED);
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> BusinessException.notFound("项目不存在: " + projectId));
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(40103, "用户不存在", HttpStatus.UNAUTHORIZED));
        if (project.getUserId() == null || !project.getUserId().equals(user.getId())) {
            throw new BusinessException(40303, "无权访问该项目", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * v4.0: 返回当前登录用户 ID（用于新数据归属）。
     */
    public String requireCurrentUserId() {
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            throw new BusinessException(40100, "未登录", HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(40103, "用户不存在", HttpStatus.UNAUTHORIZED))
                .getId();
    }
}
