package com.testagent.service;

import com.testagent.entity.Project;
import com.testagent.entity.ReconciliationReport;
import com.testagent.entity.TestCase;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ReconciliationReportRepository;
import com.testagent.repository.TestCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * v8.6.1(9.3): Milvus↔MySQL 周期对账——逐项目比对 DB 用例 id 集与向量 id 集，
 * DB 多→批量补索引；向量多→孤儿删除（终败自动落补偿表）；
 * 漂移率超阈值记 WARN。Milvus 查询失败记 SKIPPED，不误判全量缺失引发重建风暴。
 */
@Service
public class VectorReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(VectorReconciliationService.class);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ReconciliationReportRepository reportRepository;

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private SemanticService semanticService;

    // 配置三件套：yml 键 + @Value 默认值 + 字段初始化
    @Value("${app.vector.reconcile-drift-threshold:0.02}")
    private double driftThreshold = 0.02;

    public void setDriftThreshold(double driftThreshold) {
        this.driftThreshold = driftThreshold;
    }

    @Scheduled(cron = "${app.vector.reconcile-cron:0 0 2 * * *}")
    @SchedulerLock(name = "vectorReconciliation", lockAtMostFor = "PT30M")
    public void reconcileAll() {
        try {
            List<Project> projects = projectRepository.findAll();
            log.info("向量周期对账开始: 项目数 {}", projects.size());
            int repaired = 0;
            int warned = 0;
            int skipped = 0;
            for (Project project : projects) {
                ReconciliationReport report = reconcileProject(project);
                if (report == null) {
                    skipped++;
                    continue;
                }
                if (ReconciliationReport.STATUS_WARN.equals(report.getStatus())) {
                    warned++;
                } else if (ReconciliationReport.STATUS_REPAIRED.equals(report.getStatus())) {
                    repaired++;
                }
            }
            log.info("向量周期对账完成: 修复 {}, 超阈值告警 {}, 跳过 {}", repaired, warned, skipped);
        } catch (Exception e) {
            log.error("向量周期对账任务异常: {}", e.getMessage(), e);
        }
    }

    // 对账单个项目，返回报告；milvus 关闭时返回 null（不计报告）
    ReconciliationReport reconcileProject(Project project) {
        String projectId = project.getId();
        if (!milvusService.isEnabled()) {
            return null;
        }
        List<TestCase> dbCases = testCaseRepository.findByProjectId(projectId);
        Set<String> dbIds = new HashSet<>();
        for (TestCase tc : dbCases) {
            dbIds.add(tc.getId());
        }
        List<String> vecIds = milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, projectId);
        LocalDateTime now = LocalDateTime.now();
        if (vecIds == null) {
            return saveReport(projectId, dbCases.size(), -1, 1.0, 0, 0,
                    ReconciliationReport.STATUS_SKIPPED, "Milvus 查询失败，本轮跳过", now);
        }
        Set<String> vecIdSet = new HashSet<>(vecIds);

        List<String> missingInVec = new ArrayList<>(dbIds);
        missingInVec.removeAll(vecIdSet);
        List<String> orphansInVec = new ArrayList<>(vecIdSet);
        orphansInVec.removeAll(dbIds);

        int added = 0;
        int removed = 0;
        if (!missingInVec.isEmpty()) {
            // DB 有向量无 → 批量补索引（复用生成链路同款写入）
            List<TestCase> toIndex = new ArrayList<>();
            for (TestCase tc : dbCases) {
                if (missingInVec.contains(tc.getId())) {
                    toIndex.add(tc);
                }
            }
            semanticService.indexCases(projectId, toIndex);
            added = toIndex.size();
        }
        if (!orphansInVec.isEmpty()) {
            // 向量有 DB 无 → 孤儿删除（走 deleteWithRetry，终败自动落补偿表闭环衔接 9.1）
            milvusService.deleteByIds(MilvusService.COLLECTION_CASES, projectId, orphansInVec);
            removed = orphansInVec.size();
        }

        long dbCount = dbCases.size();
        long vecCount = vecIds.size();
        // v8.6.1: 注意先转 double 再除——long 整除会把所有 <100% 的漂移截断成 0
        double driftRatio = Math.abs((double) dbCount - vecCount) / Math.max(dbCount, 1);
        String status;
        String message;
        if (driftRatio > driftThreshold && (added > 0 || removed > 0)) {
            status = ReconciliationReport.STATUS_WARN;
            message = "漂移率 " + String.format("%.4f", driftRatio) + " 超阈值 " + driftThreshold
                    + "，已修复缺失 " + added + " 条、孤儿 " + removed + " 条";
            log.warn("向量对账漂移超阈值 (project={}, db={}, vec={}, ratio={}): 缺失补 {} 孤儿删 {}",
                    projectId, dbCount, vecCount, driftRatio, added, removed);
        } else if (added > 0 || removed > 0) {
            status = ReconciliationReport.STATUS_REPAIRED;
            message = "修复缺失 " + added + " 条、孤儿 " + removed + " 条";
        } else {
            status = ReconciliationReport.STATUS_OK;
            message = "一致";
        }
        return saveReport(projectId, dbCount, vecCount, driftRatio, added, removed, status, message, now);
    }

    private ReconciliationReport saveReport(String projectId, long dbCount, long vecCount,
                                            double driftRatio, int added, int removed,
                                            String status, String message, LocalDateTime now) {
        ReconciliationReport report = new ReconciliationReport();
        report.setId(UUID.randomUUID().toString().replace("-", ""));
        report.setProjectId(projectId);
        report.setDbCount(dbCount);
        report.setVecCount(vecCount);
        report.setDriftRatio(driftRatio);
        report.setRepairedAdded(added);
        report.setRepairedRemoved(removed);
        report.setStatus(status);
        report.setMessage(message);
        report.setCreatedAt(now);
        try {
            return reportRepository.save(report);
        } catch (Exception e) {
            log.error("对账报告落表失败 (project={}): {}", projectId, e.getMessage());
            return report;
        }
    }
}
