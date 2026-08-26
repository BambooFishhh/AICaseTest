package com.testagent.service;

import com.testagent.entity.PendingVectorOp;
import com.testagent.repository.PendingVectorOpRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v8.6.1(9.2): 向量删除补偿重放任务——扫描 PENDING 且到达退避时间的记录，
 * 重放成功置 DONE；失败 attempts+1 按 60s×2^attempts 指数退避；
 * 达到 max-attempts 置 DEAD 并 ERROR 告警（需人工介入）。
 * ShedLock 保证多实例部署不重复执行。
 */
@Component
public class VectorOpCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(VectorOpCompensationTask.class);
    // 退避基值：60s × 2^attempts，5 次内最大约 16 分钟
    private static final long BACKOFF_BASE_SECONDS = 60;
    private static final int BATCH_LIMIT = 50;

    @Autowired
    private PendingVectorOpRepository pendingVectorOpRepository;

    @Autowired
    private MilvusService milvusService;

    // 配置三件套：yml 键 + @Value 默认值 + 字段初始化（直 new 单测兜底）
    @Value("${app.vector.compensation-max-attempts:5}")
    private int compensationMaxAttempts = 5;

    // v8.7.1(9.5.2): 指标门面——no-op 兜底
    private com.testagent.observability.MetricsFacade metrics = new com.testagent.observability.MetricsFacade();

    @Autowired(required = false)
    void setMetrics(com.testagent.observability.MetricsFacade metrics) {
        this.metrics = metrics;
    }

    public void setCompensationMaxAttempts(int compensationMaxAttempts) {
        this.compensationMaxAttempts = compensationMaxAttempts;
    }

    @jakarta.annotation.PostConstruct
    void registerGauges() {
        // v8.7.1(9.5.2): 补偿积压量 Gauge（每次采集读库）
        metrics.setGauge("vector_pending_ops_size",
                pendingVectorOpRepository == null ? 0
                        : pendingVectorOpRepository.countByStatus(PendingVectorOp.STATUS_PENDING));
    }

    @Scheduled(fixedDelayString = "${app.vector.compensation-interval-ms:300000}",
            initialDelayString = "${app.vector.compensation-initial-delay-ms:60000}")
    @SchedulerLock(name = "vectorOpCompensation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void replayPendingOps() {
        try {
            // v8.7.1(9.5.2): 每轮刷新补偿积压 Gauge
            metrics.setGauge("vector_pending_ops_size",
                    pendingVectorOpRepository.countByStatus(PendingVectorOp.STATUS_PENDING));
            List<PendingVectorOp> due = pendingVectorOpRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                    PendingVectorOp.STATUS_PENDING, LocalDateTime.now());
            if (due.isEmpty()) {
                return;
            }
            log.info("向量补偿重放开始: 待处理 {} 条", Math.min(due.size(), BATCH_LIMIT));
            int done = 0;
            int dead = 0;
            for (PendingVectorOp op : due.subList(0, Math.min(due.size(), BATCH_LIMIT))) {
                if (replayOne(op)) {
                    done++;
                } else if (PendingVectorOp.STATUS_DEAD.equals(op.getStatus())) {
                    dead++;
                }
            }
            log.info("向量补偿重放完成: 成功 {}, 转 DEAD {}", done, dead);
        } catch (Exception e) {
            // 调度永不中断：单轮异常仅告警，下轮继续
            log.error("向量补偿重放任务异常: {}", e.getMessage(), e);
        }
    }

    // 返回是否重放成功；DEAD 转换在方法内完成
    boolean replayOne(PendingVectorOp op) {
        try {
            milvusService.deleteByRawExpr(op.getCollection(), op.getExpr());
        } catch (Exception e) {
            markFailure(op, e.getMessage());
            return false;
        }
        op.setStatus(PendingVectorOp.STATUS_DONE);
        op.setUpdatedAt(LocalDateTime.now());
        pendingVectorOpRepository.save(op);
        return true;
    }

    private void markFailure(PendingVectorOp op, String error) {
        op.setAttempts(op.getAttempts() + 1);
        op.setLastError(error);
        op.setUpdatedAt(LocalDateTime.now());
        if (op.getAttempts() >= compensationMaxAttempts) {
            op.setStatus(PendingVectorOp.STATUS_DEAD);
            pendingVectorOpRepository.save(op);
            log.error("向量补偿重放超限转 DEAD，需人工处理 (collection={}, expr={}, attempts={}): {}",
                    op.getCollection(), op.getExpr(), op.getAttempts(), error);
            return;
        }
        long backoffSeconds = BACKOFF_BASE_SECONDS * (1L << Math.min(op.getAttempts(), 10));
        op.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
        pendingVectorOpRepository.save(op);
        log.warn("向量补偿重放失败，退避 {}s 后重试 (collection={}, attempt={}/{}): {}",
                backoffSeconds, op.getCollection(), op.getAttempts(), compensationMaxAttempts, error);
    }
}
