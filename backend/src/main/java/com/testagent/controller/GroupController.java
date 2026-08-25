package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.GroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v4.3: 项目组接口。
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(groupService.list());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(groupService.create(
                (String) body.get("name"), (String) body.get("description")));
    }

    @GetMapping("/{groupId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String groupId) {
        return ApiResponse.success(groupService.detail(groupId));
    }

    @PutMapping("/{groupId}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String groupId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(groupService.update(
                groupId, (String) body.get("name"), (String) body.get("description")));
    }

    @DeleteMapping("/{groupId}")
    public ApiResponse<Void> delete(@PathVariable String groupId) {
        groupService.delete(groupId);
        return ApiResponse.success(null, "项目组已删除");
    }

    @GetMapping("/{groupId}/members")
    public ApiResponse<List<Map<String, Object>>> listMembers(@PathVariable String groupId) {
        return ApiResponse.success(groupService.listMembers(groupId));
    }

    @PostMapping("/{groupId}/members")
    public ApiResponse<Map<String, Object>> addMember(
            @PathVariable String groupId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(groupService.addMember(
                groupId, (String) body.get("userId"), (String) body.get("role")));
    }

    @PutMapping("/{groupId}/members/{userId}")
    public ApiResponse<Map<String, Object>> updateMemberRole(
            @PathVariable String groupId,
            @PathVariable String userId,
            @RequestBody Map<String, Object> body) {
        return ApiResponse.success(groupService.updateMemberRole(
                groupId, userId, (String) body.get("role")));
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public ApiResponse<Void> removeMember(@PathVariable String groupId, @PathVariable String userId) {
        groupService.removeMember(groupId, userId);
        return ApiResponse.success(null, "成员已移除");
    }
}
