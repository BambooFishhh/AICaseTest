package com.testagent.service;

import com.testagent.entity.ExecutionRecord;
import com.testagent.entity.Project;
import com.testagent.entity.TestCase;
import com.testagent.repository.CodeAnalysisRepository;
import com.testagent.repository.ExecutionRecordRepository;
import com.testagent.repository.ExecutionStepRepository;
import com.testagent.repository.GroupMemberRepository;
import com.testagent.repository.MindMapRepository;
import com.testagent.repository.ProjectGroupRepository;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.ScopeDefinitionRepository;
import com.testagent.repository.ScopeItemRepository;
import com.testagent.repository.StateMachineRepository;
import com.testagent.repository.SystemSettingRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import com.testagent.repository.TestSuiteRepository;
import com.testagent.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * v5.8: 数据健康检查——表计数、孤儿数据、Milvus 向量规模。
 */
@Service
public class DataHealthService {

    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private TestCaseRepository testCaseRepository;
    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;
    @Autowired
    private ExecutionRecordRepository executionRecordRepository;
    @Autowired
    private ExecutionStepRepository executionStepRepository;
    @Autowired
    private StateMachineRepository stateMachineRepository;
    @Autowired
    private CodeAnalysisRepository codeAnalysisRepository;
    @Autowired
    private MindMapRepository mindMapRepository;
    @Autowired
    private TestSuiteRepository testSuiteRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProjectGroupRepository projectGroupRepository;
    @Autowired
    private GroupMemberRepository groupMemberRepository;
    @Autowired
    private SystemSettingRepository systemSettingRepository;
    // v8.1: 范围表计数
    @Autowired
    private ScopeDefinitionRepository scopeDefinitionRepository;
    @Autowired
    private ScopeItemRepository scopeItemRepository;
    @Autowired
    private MilvusService milvusService;

    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableCounts", tableCounts());
        result.put("orphans", orphanCounts());

        Map<String, Object> milvus = new LinkedHashMap<>();
        milvus.put("enabled", milvusService.isEnabled());
        milvus.put("cases", milvusService.countCollection(MilvusService.COLLECTION_CASES));
        milvus.put("contexts", milvusService.countCollection(MilvusService.COLLECTION_CONTEXTS));
        milvus.put("failures", milvusService.countCollection(MilvusService.COLLECTION_FAILURES));
        result.put("milvus", milvus);
        return result;
    }

    private Map<String, Long> tableCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("projects", projectRepository.count());
        counts.put("test_cases", testCaseRepository.count());
        counts.put("test_case_versions", testCaseVersionRepository.count());
        counts.put("execution_record", executionRecordRepository.count());
        counts.put("execution_step", executionStepRepository.count());
        counts.put("state_machines", stateMachineRepository.count());
        counts.put("code_analysis", codeAnalysisRepository.count());
        counts.put("mindmaps", mindMapRepository.count());
        counts.put("test_suites", testSuiteRepository.count());
        counts.put("users", userRepository.count());
        counts.put("project_groups", projectGroupRepository.count());
        counts.put("group_members", groupMemberRepository.count());
        counts.put("system_settings", systemSettingRepository.count());
        // v8.1: 范围表
        counts.put("scope_definition", scopeDefinitionRepository.count());
        counts.put("scope_item", scopeItemRepository.count());
        return counts;
    }

    private Map<String, Long> orphanCounts() {
        Set<String> projectIds = projectRepository.findAll().stream()
                .map(Project::getId)
                .collect(Collectors.toSet());
        Set<String> caseIds = testCaseRepository.findAll().stream()
                .map(TestCase::getId)
                .collect(Collectors.toSet());
        Set<String> executionIds = executionRecordRepository.findAll().stream()
                .map(ExecutionRecord::getId)
                .collect(Collectors.toSet());

        long execOrphans = executionRecordRepository.findAll().stream()
                .filter(r -> r.getProjectId() == null || !projectIds.contains(r.getProjectId()))
                .count();
        long versionOrphans = testCaseVersionRepository.findAll().stream()
                .filter(v -> v.getTestCaseId() == null || !caseIds.contains(v.getTestCaseId()))
                .count();
        long stepOrphans = executionStepRepository.findAll().stream()
                .filter(s -> s.getExecutionId() == null || !executionIds.contains(s.getExecutionId()))
                .count();
        long suiteOrphans = testSuiteRepository.findAll().stream()
                .filter(s -> s.getProjectId() == null || !projectIds.contains(s.getProjectId()))
                .count();

        Map<String, Long> orphans = new LinkedHashMap<>();
        orphans.put("executionRecords", execOrphans);
        orphans.put("executionSteps", stepOrphans);
        orphans.put("testCaseVersions", versionOrphans);
        orphans.put("testSuites", suiteOrphans);
        return orphans;
    }
}
