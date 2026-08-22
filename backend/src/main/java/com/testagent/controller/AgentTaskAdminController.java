package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.common.BusinessException;
import com.testagent.dto.AgentTaskDTO;
import com.testagent.entity.AgentTask;
import com.testagent.service.AgentTaskService;
import com.testagent.service.TaskRetryDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v6.5: 高可用任务管理（仅 ADMIN），查看/重试中断失败任务。
 */
@RestController
@RequestMapping("/api/admin/tasks")
@CrossOrigin
public class AgentTaskAdminController {

    @Autowired
    private AgentTaskService agentTaskService;

    @Autowired
    private TaskRetryDispatcher taskRetryDispatcher;

    @GetMapping
    public ApiResponse<Page<AgentTaskDTO>> list(
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(agentTaskService
                .list(taskType, status, projectId, page, size)
                .map(AgentTaskDTO::from));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentTaskDTO> detail(@PathVariable String id) {
        AgentTask task = agentTaskService.findById(id);
        if (task == null) {
            throw BusinessException.notFound("任务不存在: " + id);
        }
        return ApiResponse.success(AgentTaskDTO.from(task));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable String id) {
        return ApiResponse.success(taskRetryDispatcher.retry(id));
    }
}
