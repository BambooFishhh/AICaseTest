package com.testagent.service;

import com.testagent.common.BusinessException;
import com.testagent.entity.GroupMember;
import com.testagent.entity.ProjectGroup;
import com.testagent.entity.User;
import com.testagent.repository.GroupMemberRepository;
import com.testagent.repository.ProjectGroupRepository;
import com.testagent.repository.UserRepository;
import com.testagent.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * v4.3: 项目组服务。
 */
@Service
public class GroupService {

    @Autowired
    private ProjectGroupRepository groupRepository;

    @Autowired
    private GroupMemberRepository memberRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Transactional
    public Map<String, Object> create(String name, String description) {
        if (name == null || name.isBlank()) {
            throw BusinessException.invalidParam("组名不能为空");
        }
        ProjectGroup group = new ProjectGroup();
        group.setId(UUID.randomUUID().toString());
        group.setName(name.trim());
        group.setDescription(description);
        group.setOwnerId(projectAccessService.requireCurrentUserId());
        groupRepository.save(group);
        return toGroupMap(group, "OWNER", 0);
    }

    public List<Map<String, Object>> list() {
        String uid = projectAccessService.requireCurrentUserId();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProjectGroup g : groupRepository.findAllByOrderByCreatedAtDesc()) {
            boolean isOwner = g.getOwnerId().equals(uid);
            Optional<GroupMember> member = memberRepository.findByGroupIdAndUserId(g.getId(), uid);
            if (!isOwner && member.isEmpty()) continue;
            String myRole = isOwner ? "OWNER" : member.get().getRole();
            long memberCount = memberRepository.findByGroupId(g.getId()).size();
            result.add(toGroupMap(g, myRole, (int) memberCount));
        }
        return result;
    }

    public Map<String, Object> detail(String groupId) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupView(group);
        long memberCount = memberRepository.findByGroupId(groupId).size();
        String myRole = group.getOwnerId().equals(projectAccessService.requireCurrentUserId())
                ? "OWNER" : memberRepository.findByGroupIdAndUserId(groupId,
                        projectAccessService.requireCurrentUserId()).map(GroupMember::getRole).orElse("VIEWER");
        return toGroupMap(group, myRole, (int) memberCount);
    }

    @Transactional
    public Map<String, Object> update(String groupId, String name, String description) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupOwner(group);
        if (name != null && !name.isBlank()) group.setName(name.trim());
        group.setDescription(description);
        groupRepository.save(group);
        return toGroupMap(group, "OWNER", memberRepository.findByGroupId(groupId).size());
    }

    @Transactional
    public void delete(String groupId) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupOwner(group);
        memberRepository.deleteByGroupId(groupId);
        groupRepository.delete(group);
    }

    // ===== 成员管理（仅组创建者） =====

    public List<Map<String, Object>> listMembers(String groupId) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupView(group);
        List<Map<String, Object>> result = new ArrayList<>();
        for (GroupMember m : memberRepository.findByGroupId(groupId)) {
            User u = userRepository.findById(m.getUserId()).orElse(null);
            if (u == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", u.getId());
            item.put("username", u.getUsername());
            item.put("displayName", u.getDisplayName());
            item.put("role", m.getRole());
            result.add(item);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> addMember(String groupId, String userId, String role) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupOwner(group);
        if (userId == null || userId.isBlank()) throw BusinessException.invalidParam("userId 不能为空");
        if (group.getOwnerId().equals(userId)) {
            throw new BusinessException(50013, "组创建者无需添加为成员", HttpStatus.BAD_REQUEST);
        }
        String r = normalizeRole(role);
        GroupMember member = memberRepository.findByGroupIdAndUserId(groupId, userId).orElse(null);
        if (member == null) {
            member = new GroupMember();
            member.setId(UUID.randomUUID().toString());
            member.setGroupId(groupId);
            member.setUserId(userId);
        }
        member.setRole(r);
        memberRepository.save(member);
        User u = userRepository.findById(userId).orElseThrow(() -> BusinessException.notFound("用户不存在"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", u.getId());
        item.put("username", u.getUsername());
        item.put("displayName", u.getDisplayName());
        item.put("role", r);
        return item;
    }

    @Transactional
    public Map<String, Object> updateMemberRole(String groupId, String userId, String role) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupOwner(group);
        GroupMember member = memberRepository.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> BusinessException.notFound("成员不存在"));
        member.setRole(normalizeRole(role));
        memberRepository.save(member);
        User u = userRepository.findById(userId).orElse(null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", u != null ? u.getId() : userId);
        item.put("username", u != null ? u.getUsername() : userId);
        item.put("displayName", u != null ? u.getDisplayName() : userId);
        item.put("role", member.getRole());
        return item;
    }

    @Transactional
    public void removeMember(String groupId, String userId) {
        ProjectGroup group = requireGroup(groupId);
        assertGroupOwner(group);
        memberRepository.findByGroupIdAndUserId(groupId, userId)
                .ifPresent(memberRepository::delete);
    }

    private String normalizeRole(String role) {
        String r = role == null ? "VIEWER" : role.toUpperCase();
        if (!"VIEWER".equals(r) && !"OPERATOR".equals(r)) {
            throw BusinessException.invalidParam("非法的成员角色: " + role);
        }
        return r;
    }

    private ProjectGroup requireGroup(String groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> BusinessException.notFound("项目组不存在: " + groupId));
    }

    private void assertGroupOwner(ProjectGroup group) {
        String uid = projectAccessService.requireCurrentUserId();
        if (!group.getOwnerId().equals(uid) && !SecurityUtils.isAdmin()) {
            throw new BusinessException(40305, "仅组创建者可执行该操作", HttpStatus.FORBIDDEN);
        }
    }

    private void assertGroupView(ProjectGroup group) {
        String uid = projectAccessService.requireCurrentUserId();
        if (!group.getOwnerId().equals(uid) && !SecurityUtils.isAdmin()
                && memberRepository.findByGroupIdAndUserId(group.getId(), uid).isEmpty()) {
            throw new BusinessException(40303, "无权访问该项目组", HttpStatus.FORBIDDEN);
        }
    }

    private Map<String, Object> toGroupMap(ProjectGroup g, String myRole, int memberCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("description", g.getDescription());
        m.put("ownerId", g.getOwnerId());
        m.put("memberCount", memberCount);
        m.put("myRole", myRole);
        m.put("createdAt", g.getCreatedAt());
        return m;
    }
}
