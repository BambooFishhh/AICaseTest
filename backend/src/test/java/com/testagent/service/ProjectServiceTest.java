package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.ExecutionStep;
import com.testagent.entity.Project;
import com.testagent.entity.TestSuite;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import com.testagent.repository.MindMapRepository;
import com.testagent.repository.ProjectGroupRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import com.testagent.repository.TestSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * vT6: 项目删除级联清理单元测试。
 */
class ProjectServiceTest {

    private ProjectService service;
    private ProjectRepository projectRepository;
    private CodeAnalysisRepository codeAnalysisRepository;
    private StateMachineRepository stateMachineRepository;
    private TestCaseRepository testCaseRepository;
    private ExecutionRecordRepository executionRecordRepository;
    private ExecutionStepRepository executionStepRepository;
    private TestSuiteRepository testSuiteRepository;
    private TestCaseVersionRepository testCaseVersionRepository;
    private MindMapRepository mindMapRepository;
    private SemanticService semanticService;
    private ProjectAccessService projectAccessService;

    @BeforeEach
    void setUp() {
        service = new ProjectService();
        projectRepository = mock(ProjectRepository.class);
        codeAnalysisRepository = mock(CodeAnalysisRepository.class);
        stateMachineRepository = mock(StateMachineRepository.class);
        testCaseRepository = mock(TestCaseRepository.class);
        executionRecordRepository = mock(ExecutionRecordRepository.class);
        executionStepRepository = mock(ExecutionStepRepository.class);
        testSuiteRepository = mock(TestSuiteRepository.class);
        testCaseVersionRepository = mock(TestCaseVersionRepository.class);
        mindMapRepository = mock(MindMapRepository.class);
        semanticService = mock(SemanticService.class);
        projectAccessService = mock(ProjectAccessService.class);

        ReflectionTestUtils.setField(service, "projectRepository", projectRepository);
        ReflectionTestUtils.setField(service, "codeAnalysisRepository", codeAnalysisRepository);
        ReflectionTestUtils.setField(service, "stateMachineRepository", stateMachineRepository);
        ReflectionTestUtils.setField(service, "testCaseRepository", testCaseRepository);
        ReflectionTestUtils.setField(service, "executionRecordRepository", executionRecordRepository);
        ReflectionTestUtils.setField(service, "executionStepRepository", executionStepRepository);
        ReflectionTestUtils.setField(service, "testSuiteRepository", testSuiteRepository);
        ReflectionTestUtils.setField(service, "testCaseVersionRepository", testCaseVersionRepository);
        ReflectionTestUtils.setField(service, "mindMapRepository", mindMapRepository);
        ReflectionTestUtils.setField(service, "semanticService", semanticService);
        ReflectionTestUtils.setField(service, "projectAccessService", projectAccessService);
    }

    @Test
    void deleteProjectCascadesAllRelatedData() {
        Project project = new Project();
        project.setId("p1");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));

        ExecutionRecord record = new ExecutionRecord();
        record.setId("e1");
        when(executionRecordRepository.findByProjectIdOrderByStartTimeDesc("p1"))
                .thenReturn(List.of(record));
        when(executionStepRepository.findByExecutionIdIn(List.of("e1")))
                .thenReturn(List.of(new ExecutionStep()));
        when(testSuiteRepository.findByProjectIdOrderByCreatedAtDesc("p1"))
                .thenReturn(List.of(new TestSuite()));
        when(codeAnalysisRepository.findAllByProjectId("p1")).thenReturn(List.of());
        when(mindMapRepository.findAllByProjectId("p1")).thenReturn(List.of());

        service.deleteProject("p1");

        verify(executionStepRepository).deleteAll(anyList());
        verify(executionRecordRepository).deleteAll(List.of(record));
        verify(testSuiteRepository).deleteAll(anyList());
        verify(testCaseVersionRepository).deleteByProjectId("p1");
        verify(semanticService).clearProject("p1");
        verify(projectRepository).delete(project);
    }
}
