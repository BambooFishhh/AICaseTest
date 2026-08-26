package com.testagent.service;

import com.testagent.entity.Project;
import com.testagent.entity.ReconciliationReport;
import com.testagent.entity.TestCase;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ReconciliationReportRepository;
import com.testagent.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// v8.6.1(9.3): 周期对账分支——正常 / 缺失重建 / 孤儿删除 / 超阈值 WARN / 查询失败 SKIPPED
class VectorReconciliationServiceTest {

    private VectorReconciliationService service;
    private ProjectRepository projectRepository;
    private TestCaseRepository testCaseRepository;
    private ReconciliationReportRepository reportRepository;
    private MilvusService milvusService;
    private SemanticService semanticService;

    @BeforeEach
    void setUp() {
        service = new VectorReconciliationService();
        projectRepository = mock(ProjectRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        reportRepository = mock(ReconciliationReportRepository.class);
        milvusService = mock(MilvusService.class);
        semanticService = mock(SemanticService.class);
        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
        ReflectionTestUtils.setField(service, "testCaseRepository", testCaseRepository);
        ReflectionTestUtils.setField(service, "reportRepository", reportRepository);
        ReflectionTestUtils.setField(service, "milvusService", milvusService);
        ReflectionTestUtils.setField(service, "semanticService", semanticService);
        service.setDriftThreshold(0.02);
        when(reportRepository.save(org.mockito.ArgumentMatchers.any(ReconciliationReport.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private TestCase caseOf(String id) {
        TestCase tc = new TestCase();
        tc.setId(id);
        tc.setProjectId("p1");
        return tc;
    }

    @Test
    void consistentProjectRecordsOkWithoutRepairs() {
        when(milvusService.isEnabled()).thenReturn(true);
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of(caseOf("TC-1"), caseOf("TC-2")));
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1"))
                .thenReturn(List.of("TC-1", "TC-2"));

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        assertEquals(ReconciliationReport.STATUS_OK, report.getStatus());
        assertEquals(0, report.getRepairedAdded());
        assertEquals(0, report.getRepairedRemoved());
        verify(semanticService, never()).indexCases(anyString(), anyList());
        verify(milvusService, never()).deleteByIds(anyString(), anyString(), anyList());
    }

    @Test
    void missingVectorsAreReindexed() {
        when(milvusService.isEnabled()).thenReturn(true);
        when(testCaseRepository.findByProjectId("p1"))
                .thenReturn(List.of(caseOf("TC-1"), caseOf("TC-2"), caseOf("TC-3")));
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1"))
                .thenReturn(List.of("TC-1"));

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        // 3 条缺 2 → 漂移率 0.667 超阈值：WARN 与修复并存
        assertEquals(ReconciliationReport.STATUS_WARN, report.getStatus());
        assertEquals(2, report.getRepairedAdded());
        verify(semanticService).indexCases(eq("p1"), anyList());
    }

    @Test
    void orphanVectorsAreDeleted() {
        when(milvusService.isEnabled()).thenReturn(true);
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of(caseOf("TC-1")));
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1"))
                .thenReturn(List.of("TC-1", "GHOST-9"));

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        // 单用例项目删 1 孤儿 → 漂移率 1.0 超阈值，WARN 与修复并存（WARN 优先）
        assertEquals(ReconciliationReport.STATUS_WARN, report.getStatus());
        assertEquals(1, report.getRepairedRemoved());
        verify(milvusService).deleteByIds(MilvusService.COLLECTION_CASES, "p1", List.of("GHOST-9"));
    }

    @Test
    void driftBeyondThresholdMarksWarn() {
        when(milvusService.isEnabled()).thenReturn(true);
        List<TestCase> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add(caseOf("TC-" + i));
        }
        when(testCaseRepository.findByProjectId("p1")).thenReturn(many);
        // 90/100 → 漂移率 0.1 > 0.02，且存在缺失触发修复
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1"))
                .thenReturn(List.of("TC-1", "TC-2", "TC-3"));

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        assertEquals(ReconciliationReport.STATUS_WARN, report.getStatus());
        assertEquals(97, report.getRepairedAdded());
        assertNotNull(report.getMessage());
    }

    @Test
    void smallDriftRepairStaysRepairedNotWarn() {
        when(milvusService.isEnabled()).thenReturn(true);
        List<TestCase> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add(caseOf("TC-" + i));
        }
        when(testCaseRepository.findByProjectId("p1")).thenReturn(many);
        // 99/100 → 漂移率 0.01 ≤ 0.02，有修复但不过阈值 → REPAIRED 而非 WARN
        List<String> vecIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 99; i++) {
            vecIds.add("TC-" + i);
        }
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1")).thenReturn(vecIds);

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        assertEquals(ReconciliationReport.STATUS_REPAIRED, report.getStatus());
        assertEquals(0.01, report.getDriftRatio(), 1e-9);
    }

    @Test
    void queryFailureSkipsInsteadOfMassRebuild() {
        when(milvusService.isEnabled()).thenReturn(true);
        when(testCaseRepository.findByProjectId("p1")).thenReturn(List.of(caseOf("TC-1")));
        // null = Milvus 查询失败（区别于合法空集）
        when(milvusService.queryIdsByProject(MilvusService.COLLECTION_CASES, "p1")).thenReturn(null);

        ReconciliationReport report = service.reconcileProject(projectOf("p1"));

        assertEquals(ReconciliationReport.STATUS_SKIPPED, report.getStatus());
        verify(semanticService, never()).indexCases(anyString(), anyList());
    }

    @Test
    void disabledMilvusReturnsNullReport() {
        when(milvusService.isEnabled()).thenReturn(false);

        assertNull(service.reconcileProject(projectOf("p1")));
    }

    private Project projectOf(String id) {
        Project p = new Project();
        p.setId(id);
        return p;
    }
}
