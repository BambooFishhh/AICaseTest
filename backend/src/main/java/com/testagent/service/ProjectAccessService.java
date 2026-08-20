package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.entity.GroupMember;
import com.testagent.entity.Project;
import com.testagent.entity.User;
import com.testagent.repository.GroupMemberRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.UserRepository;
import com.testagent.security.AccessLevel;
import com.testagent.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v4.3: 项目访问控制——OWNER/OPERATOR/VIEWER 分级。
 */
@Service
public class ProjectAccessService {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GroupMemberRepository groupMemberRepository;

    public AccessLevel getAccessLevel(String projectId) {
        if (SecurityUtils.isAdmin()) {
            return AccessLevel.OWNER;
        }
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            return AccessLevel.NONE;
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return AccessLevel.NONE;
        }
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return AccessLevel.NONE;
        }
        // 项目创建者
        if (project.getUserId() != null && project.getUserId().equals(user.getId())) {
            return AccessLevel.OWNER;
        }
        // 组内成员
        if (project.getGroupId() != null) {
            Optional<GroupMember> member = groupMemberRepository
                    .findByGroupIdAndUserId(project.getGroupId(), user.getId());
            if (member.isPresent()) {
                return "OPERATOR".equals(member.get().getRole())
                        ? AccessLevel.OPERATOR : AccessLevel.VIEWER;
            }
        }
        return AccessLevel.NONE;
    }

    /**
     * v6.6: 批量计算项目访问级别，消除项目列表 N+1。
     * 当前用户与组成员关系只查询一次，不再对每个项目重复查表。
     */
    public Map<String, AccessLevel> getAccessLevels(Collection<String> projectIds) {
        Map<String, AccessLevel> result = new HashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return result;
        }
        if (SecurityUtils.isAdmin()) {
            for (String id : projectIds) {
                result.put(id, AccessLevel.OWNER);
            }
            return result;
        }
        String username = SecurityUtils.currentUsername();
        if (username == null) {
            return result;
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return result;
        }
        List<Project> projects = projectRepository.findAllById(projectIds);
        Map<String, String> groupRoles = new HashMap<>();
        for (GroupMember member : groupMemberRepository.findByUserId(user.getId())) {
            groupRoles.put(member.getGroupId(), member.getRole());
        }
        for (Project project : projects) {
            AccessLevel level = AccessLevel.NONE;
            if (project.getUserId() != null && project.getUserId().equals(user.getId())) {
                level = AccessLevel.OWNER;
            } else if (project.getGroupId() != null) {
                String role = groupRoles.get(project.getGroupId());
                if (role != null) {
                    level = "OPERATOR".equals(role) ? AccessLevel.OPERATOR : AccessLevel.VIEWER;
                }
            }
            result.put(project.getId(), level);
        }
        return result;
    }

    public void assertViewAccess(String projectId) {
        AccessLevel level = getAccessLevel(projectId);
        if (level == AccessLevel.NONE) {
            throw new BusinessException(40303, "无权访问该项目", HttpStatus.FORBIDDEN);
        }
    }

    public void assertOperateAccess(String projectId) {
        AccessLevel level = getAccessLevel(projectId);
        if (level == AccessLevel.VIEWER) {
            throw new BusinessException(40304, "只读权限，无法执行该操作", HttpStatus.FORBIDDEN);
        }
        if (level == AccessLevel.NONE) {
            throw new BusinessException(40303, "无权访问该项目", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 返回当前登录用户 ID（用于新数据归属）。
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
