package com.testagent.service;

import com.testagent.agent.ScopeMappingAgent;
import com.testagent.entity.CodeAnalysis;
import com.testagent.entity.Project;
import com.testagent.entity.ScopeDefinition;
import com.testagent.entity.ScopeItem;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ScopeDefinitionRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v9.0: 分析完成后自动识别本期范围——基线自动回退主干、识别出条目即锁定、
 * 重新分析 = 删除重建；已确认范围放开条目增删（剔除噪声/补充遗漏）。
 */
@ExtendWith(MockitoExtension.class)
class ScopeServiceAutoSyncTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectAccessService projectAccessService;
    @Mock
    private GitDiffService gitDiffService;
    @Mock
    private ScopeDefinitionRepository definitionRepository;
    @Mock
    private ScopeItemRepository itemRepository;
    @Mock
    private CodeAnalysisRepository codeAnalysisRepository;
    @Mock
    private StateMachineRepository stateMachineRepository;
    @Mock
    private ScopeMappingAgent scopeMappingAgent;

    @InjectMocks
    private ScopeService scopeService;

    private Project mockGitProject() {
        Project project = new Project();
        project.setId("p1");
        project.setSourcePath("/repo");
        project.setPrdContent("PRD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(gitDiffService.isGitRepo("/repo")).thenReturn(true);
        lenient().when(definitionRepository.save(any(ScopeDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        return project;
    }

    private CodeAnalysis mockAnalysis() {
        CodeAnalysis analysis = new CodeAnalysis("a1", "p1");
        analysis.setCreatedAt(LocalDateTime.now());
        analysis.setBackendResult(
                "{\"endpoints\":[{\"method\":\"GET\",\"path\":\"/wx/order/list\",\"file\":\"src/main/java/Foo.java\"}]}");
        analysis.setFrontendResult("{}");
        when(codeAnalysisRepository.findAllByProjectId("p1")).thenReturn(List.of(analysis));
        return analysis;
    }

    private void mockIdentificationPipeline() {
        when(stateMachineRepository.findByProjectId("p1")).thenReturn(List.of());
        when(scopeMappingAgent.map(anyString(), anyList())).thenReturn(List.of());
    }

    @Test
    void autoSyncCreatesAndConfirmsScope() {
        mockGitProject();
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        when(gitDiffService.detectDefaultBaseline("/repo")).thenReturn("master");
        when(gitDiffService.diffFiles(eq("/repo"), eq("master"), any()))
                .thenReturn(List.of(Map.of("status", "M", "path", "src/main/java/Foo.java")));
        mockAnalysis();
        mockIdentificationPipeline();
        when(itemRepository.countByDefinitionId(anyString())).thenReturn(1L);

        scopeService.autoSyncAfterAnalysis("p1");

        // save 多次（初始 + runIdentification 内部 + 确认），同一实例被原地改状态，断言最终值
        ArgumentCaptor<ScopeDefinition> captor = ArgumentCaptor.forClass(ScopeDefinition.class);
        verify(definitionRepository, atLeastOnce()).save(captor.capture());
        ArgumentCaptor<List<ScopeItem>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(itemsCaptor.capture());
        // 有条目 → 自动确认锁定；基线为探测到的默认主干
        ScopeDefinition def = captor.getValue();
        assertEquals(ScopeDefinition.STATUS_CONFIRMED, def.getStatus());
        assertEquals("本期范围", def.getName());
        assertEquals("master", def.getBaselineRef());
        assertEquals(1, itemsCaptor.getValue().size());
    }

    @Test
    void autoSyncReplacesExistingScope() {
        mockGitProject();
        ScopeDefinition old = new ScopeDefinition();
        old.setId("old");
        old.setProjectId("p1");
        old.setStatus(ScopeDefinition.STATUS_CONFIRMED);
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of(old));
        when(gitDiffService.detectDefaultBaseline("/repo")).thenReturn("master");
        when(gitDiffService.diffFiles(eq("/repo"), eq("master"), any()))
                .thenReturn(List.of(Map.of("status", "M", "path", "src/main/java/Foo.java")));
        mockAnalysis();
        mockIdentificationPipeline();
        when(itemRepository.countByDefinitionId(anyString())).thenReturn(1L);

        scopeService.autoSyncAfterAnalysis("p1");

        // 重新分析 = 删除重建：旧范围连同条目一起删除
        verify(itemRepository).deleteByDefinitionId("old");
        verify(definitionRepository).delete(old);
        ArgumentCaptor<ScopeDefinition> captor = ArgumentCaptor.forClass(ScopeDefinition.class);
        verify(definitionRepository, atLeastOnce()).save(captor.capture());
        assertEquals(ScopeDefinition.STATUS_CONFIRMED, captor.getValue().getStatus());
    }

    @Test
    void autoSyncSkipsNonGitRepo() {
        Project project = new Project();
        project.setId("p1");
        project.setSourcePath("/folder");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(gitDiffService.isGitRepo("/folder")).thenReturn(false);

        scopeService.autoSyncAfterAnalysis("p1");

        verify(definitionRepository, never()).save(any(ScopeDefinition.class));
        verify(itemRepository, never()).saveAll(anyList());
    }

    @Test
    void autoSyncSkipsWhenNoAnalysis() {
        mockGitProject();
        // findAllByProjectId 默认返回空列表 → 无分析结果，早退且不动旧范围

        scopeService.autoSyncAfterAnalysis("p1");

        verify(definitionRepository, never()).save(any(ScopeDefinition.class));
        verify(definitionRepository, never()).delete(any(ScopeDefinition.class));
    }

    @Test
    void autoSyncSkipsWhenNoDefaultBaseline() {
        mockGitProject();
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        when(gitDiffService.detectDefaultBaseline("/repo")).thenReturn(null);
        mockAnalysis();

        scopeService.autoSyncAfterAnalysis("p1");

        verify(definitionRepository, never()).save(any(ScopeDefinition.class));
    }

    @Test
    void autoSyncEmptyDiffCreatesNothing() {
        mockGitProject();
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        when(gitDiffService.detectDefaultBaseline("/repo")).thenReturn("master");
        when(gitDiffService.diffFiles(eq("/repo"), eq("master"), any())).thenReturn(List.of());
        mockAnalysis();

        scopeService.autoSyncAfterAnalysis("p1");

        // 基线与 HEAD 无差异：回滚草稿定义，不产出范围
        ArgumentCaptor<ScopeDefinition> captor = ArgumentCaptor.forClass(ScopeDefinition.class);
        verify(definitionRepository).save(captor.capture());
        verify(definitionRepository).delete(captor.getValue());
        verify(itemRepository, never()).saveAll(anyList());
    }

    @Test
    void autoSyncZeroItemsLeavesDraft() {
        mockGitProject();
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        when(gitDiffService.detectDefaultBaseline("/repo")).thenReturn("master");
        // 仅非前后端文件变更：识别不出任何条目
        when(gitDiffService.diffFiles(eq("/repo"), eq("master"), any()))
                .thenReturn(List.of(Map.of("status", "M", "path", "docs/readme.md")));
        mockAnalysis();
        mockIdentificationPipeline();
        when(itemRepository.countByDefinitionId(anyString())).thenReturn(0L);

        scopeService.autoSyncAfterAnalysis("p1");

        ArgumentCaptor<ScopeDefinition> captor = ArgumentCaptor.forClass(ScopeDefinition.class);
        verify(definitionRepository, atLeastOnce()).save(captor.capture());
        // 0 条目不自动确认，保留草稿待手动补充
        assertEquals(ScopeDefinition.STATUS_DRAFT, captor.getValue().getStatus());
    }

    @Test
    void confirmedScopeAllowsItemEdit() {
        ScopeDefinition confirmed = new ScopeDefinition();
        confirmed.setId("d1");
        confirmed.setProjectId("p1");
        confirmed.setStatus(ScopeDefinition.STATUS_CONFIRMED);
        when(definitionRepository.findById("d1")).thenReturn(Optional.of(confirmed));

        scopeService.addItem("p1", "d1", "ENDPOINT", "GET /wx/order/list", "MODIFIED", "补充");
        verify(itemRepository).save(any(ScopeItem.class));

        ScopeItem item = new ScopeItem();
        item.setId("i1");
        item.setDefinitionId("d1");
        when(itemRepository.findById("i1")).thenReturn(Optional.of(item));
        scopeService.removeItem("p1", "d1", "i1");
        verify(itemRepository).delete(item);
    }
}
