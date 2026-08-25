package com.testagent.controller;

import com.testagent.common.ApiResponse;
import com.testagent.service.AgentTaskService;
import com.testagent.service.TaskQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5.3: 任务队列统计。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    @Autowired
    private TaskQueueService taskQueueService;

    @Autowired
    private AgentTaskService agentTaskService;

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> result = taskQueueService.stats();
        // v6.5: 高可用任务状态统计（前台队列计数之外的任务生命周期口径）
        result.put("agentTasks", agentTaskService.statusCounts());
        return ApiResponse.success(result);
    }
}
