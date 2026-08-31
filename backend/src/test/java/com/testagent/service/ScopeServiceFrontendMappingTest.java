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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v8.9.8: 范围模型前端维度——前端变更文件映射为 PAGE 条目（纯前端迭代不再识别为 0 条），
 * 分析结果时效性（旧于 HEAD 提交）向前端透出提示。
 */
@ExtendWith(MockitoExtension.class)
class ScopeServiceFrontendMappingTest {

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

    private void mockBaselineProject(String sourcePath) {
        Project project = new Project();
        project.setId("p1");
        project.setSourcePath(sourcePath);
        project.setPrdContent("PRD");
        when(projectRepository.findById("p1")).thenReturn(Optional.of(project));
        when(definitionRepository.findByProjectIdOrderByCreatedAtDesc("p1")).thenReturn(List.of());
        when(gitDiffService.isGitRepo(sourcePath)).thenReturn(true);
        when(stateMachineRepository.findByProjectId("p1")).thenReturn(List.of());
        when(scopeMappingAgent.map(anyString(), anyList())).thenReturn(List.of());
        lenient().when(itemRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(definitionRepository.save(any(ScopeDefinition.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void frontendOnlyChangesMapToPageItems() {
        mockBaselineProject("/repo");
        // diff：新增路由文件 + 修改一个未匹配路由的页面组件（纯前端迭代）
        when(gitDiffService.diffFiles(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of("status", "A", "path", "src/router/index.js"),
                Map.of("status", "M", "path", "src/views/Collect.vue")));
        // 分析结果：路由 /collect 定义在 src/router/index.js
        CodeAnalysis analysis = new CodeAnalysis("a1", "p1");
        analysis.setCreatedAt(LocalDateTime.now());
        analysis.setBackendResult("{}");
        analysis.setFrontendResult("{\"routes\":[{\"path\":\"/collect\",\"name\":\"collect\",\"file\":\"index.js\"}]}");
        when(codeAnalysisRepository.findAllByProjectId("p1")).thenReturn(List.of(analysis));
        when(gitDiffService.headCommitEpoch("/repo")).thenReturn(null);

        Map<String, Object> r = scopeService.createDraft("p1", "S36", "master");

        assertEquals(true, r.get("autoIdentified"));
        ArgumentCaptor<List<ScopeItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(captor.capture());
        List<ScopeItem> items = captor.getValue();
        // 路由命中出一条 PAGE；未匹配路由的 Collect.vue 聚合为一条兜底项 → 纯前端迭代范围非空可确认
        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(i -> ScopeItem.TYPE_PAGE.equals(i.getItemType())));
        ScopeItem routeItem = items.stream()
                .filter(i -> "/collect".equals(i.getItemRef())).findFirst().orElseThrow();
        assertEquals(ScopeItem.KIND_ADDED, routeItem.getChangeKind());
        assertEquals(ScopeItem.ORIGIN_AUTO_DIFF, routeItem.getOrigin());
        assertTrue(items.stream().anyMatch(i -> "frontend-files".equals(i.getItemRef())));
    }

    @Test
    void staleAnalysisFlagExposedToCaller() {
        mockBaselineProject("/repo");
        when(gitDiffService.diffFiles(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of("status", "M", "path", "src/main/java/Foo.java")));
        // 分析早于 HEAD 提交（epoch 秒）→ analysisStale=true，提示重分析后重算
        CodeAnalysis analysis = new CodeAnalysis("a1", "p1");
        analysis.setCreatedAt(LocalDateTime.now().minusDays(1));
        analysis.setBackendResult("{}");
        analysis.setFrontendResult("{}");
        when(codeAnalysisRepository.findAllByProjectId("p1")).thenReturn(List.of(analysis));
        when(gitDiffService.headCommitEpoch("/repo"))
                .thenReturn(System.currentTimeMillis() / 1000);

        Map<String, Object> r = scopeService.createDraft("p1", "S36", "master");

        assertEquals(true, r.get("analysisStale"));
    }

    @Test
    void freshAnalysisNotFlaggedStale() {
        mockBaselineProject("/repo");
        when(gitDiffService.diffFiles(anyString(), anyString(), any())).thenReturn(List.of(
                Map.of("status", "M", "path", "src/main/java/Foo.java")));
        CodeAnalysis analysis = new CodeAnalysis("a1", "p1");
        analysis.setCreatedAt(LocalDateTime.now());
        analysis.setBackendResult("{}");
        analysis.setFrontendResult("{}");
        when(codeAnalysisRepository.findAllByProjectId("p1")).thenReturn(List.of(analysis));
        // HEAD 提交早于分析时间 → 新鲜
        when(gitDiffService.headCommitEpoch("/repo"))
                .thenReturn(System.currentTimeMillis() / 1000 - 86400);

        Map<String, Object> r = scopeService.createDraft("p1", "S36", "master");

        assertFalse((Boolean) r.get("analysisStale"));
    }
}
