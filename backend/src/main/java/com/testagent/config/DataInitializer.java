package com.testagent.config;

import com.testagent.entity.Project;
import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.User;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.UserRepository;
import com.testagent.service.TaskQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

/**
 * v4.0: 启动初始化——默认管理员 + 存量项目归属迁移。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TaskQueueService taskQueueService;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        User admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            admin = new User();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setDisplayName("管理员");
            admin.setRole("ADMIN");
            userRepository.save(admin);
            log.info("v4.0: 已创建默认管理员 admin（请尽快修改初始密码）");
        }

        // 存量项目归属迁移：userId 为空 → 归默认管理员
        List<Project> orphanProjects = projectRepository.findAll().stream()
                .filter(p -> p.getUserId() == null || p.getUserId().isBlank())
                .toList();
        if (!orphanProjects.isEmpty()) {
            for (Project p : orphanProjects) {
                p.setUserId(admin.getId());
                projectRepository.save(p);
            }
            log.info("v4.0: 已将 {} 个存量项目归属到默认管理员", orphanProjects.size());
        }

        // vP2: 启动恢复——清空残留任务队列，恢复卡死的分析/生成项目状态
        taskQueueService.recoverStaleTasks();
        List<Project> stuckProjects = projectRepository.findAll().stream()
                .filter(p -> "analyzing".equals(p.getStatus()) || "generating".equals(p.getStatus()))
                .toList();
        for (Project p : stuckProjects) {
            boolean hasCases = !testCaseRepository.findByProjectId(p.getId()).isEmpty();
            if (hasCases) {
                projectRepository.updateStatus(p.getId(), "completed");
            } else {
                projectRepository.updateStatusWithError(p.getId(), "failed", "服务重启导致任务中断，请重新执行");
            }
        }
        if (!stuckProjects.isEmpty()) {
            log.info("vP2: 启动恢复 {} 个卡死的项目任务状态", stuckProjects.size());
        }

        // 启动清扫：重启后仍在 running/pending 的执行记录已无存活 worker，标记中断/取消
        List<ExecutionRecord> stuck = new java.util.ArrayList<>();
        stuck.addAll(executionRecordRepository.findByStatus("running"));
        stuck.addAll(executionRecordRepository.findByStatus("pending"));
        for (ExecutionRecord r : stuck) {
            boolean wasRunning = "running".equals(r.getStatus());
            r.setStatus(wasRunning ? "failed" : "cancelled");
            r.setEndTime(LocalDateTime.now());
            r.setSummary(wasRunning ? "服务重启导致执行中断" : "已取消（服务重启，任务未开始）");
            if (wasRunning) {
                r.setErrorMessage("服务重启，执行已中断");
            }
            executionRecordRepository.save(r);
            // 用例执行状态恢复未执行
            testCaseRepository.findById(r.getTestCaseId()).ifPresent(tc -> {
                tc.setExecutionStatus("not_executed");
                testCaseRepository.save(tc);
            });
        }
        if (!stuck.isEmpty()) {
            log.info("v4.3: 启动清扫 {} 条卡死的执行记录", stuck.size());
        }
    }
}
