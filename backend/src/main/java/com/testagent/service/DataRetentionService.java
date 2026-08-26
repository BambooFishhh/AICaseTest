package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v5.8: 执行数据保留策略。默认关闭（execution-days=0），开启后每天按 cron 清理终态历史。
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    @Value("${app.retention.execution-days:0}")
    private int retentionDays;

    @Autowired
    private ExecutionRecordRepository executionRecordRepository;

    @Autowired
    private ExecutionStepRepository executionStepRepository;

    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    // v8.8.2(10.4): 双实例就绪——保留策略清理任务上锁
    @SchedulerLock(name = "dataRetentionClean", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cleanOldExecutions() {
        if (retentionDays <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<ExecutionRecord> old = executionRecordRepository.findByEndTimeBeforeAndStatusIn(
                cutoff, List.of("passed", "failed", "cancelled"));
        if (old.isEmpty()) {
            return;
        }
        List<String> ids = old.stream().map(ExecutionRecord::getId).toList();
        executionStepRepository.deleteAll(executionStepRepository.findByExecutionIdIn(ids));
        for (ExecutionRecord r : old) {
            deleteQuietly(r.getRecordingVideoPath());
            deleteQuietly("outputs/evidence/" + r.getId() + ".md");
        }
        executionRecordRepository.deleteAll(old);
        log.info("Data retention cleaned {} execution records before {}", old.size(), cutoff);
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (Exception ignored) {
            // 文件不存在或占用时不阻塞清理
        }
    }
}
