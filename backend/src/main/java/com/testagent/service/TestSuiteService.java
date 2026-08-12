package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.common.BusinessException;
import com.testagent.entity.TestSuite;
import com.testagent.repository.TestSuiteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v3.15: 测试集/回归集服务。
 */
@Service
public class TestSuiteService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Transactional
    public Map<String, Object> create(String projectId, String name, List<String> caseIds) {
        projectAccessService.assertProjectAccess(projectId);
        if (name == null || name.isBlank()) {
            throw new BusinessException(50006, "测试集名称不能为空", HttpStatus.BAD_REQUEST);
        }
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(50007, "请至少选择一个用例", HttpStatus.BAD_REQUEST);
        }
        TestSuite suite = new TestSuite();
        suite.setId(UUID.randomUUID().toString());
        suite.setProjectId(projectId);
        suite.setName(name.trim());
        try {
            suite.setCaseIds(objectMapper.writeValueAsString(caseIds));
        } catch (Exception e) {
            suite.setCaseIds("[]");
        }
        testSuiteRepository.save(suite);
        return toMap(suite);
    }

    public List<Map<String, Object>> list(String projectId) {
        projectAccessService.assertProjectAccess(projectId);
        return testSuiteRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toMap)
                .toList();
    }

    @Transactional
    public void delete(String projectId, String suiteId) {
        projectAccessService.assertProjectAccess(projectId);
        TestSuite suite = testSuiteRepository.findById(suiteId)
                .orElseThrow(() -> BusinessException.notFound("测试集不存在: " + suiteId));
        if (!projectId.equals(suite.getProjectId())) {
            throw new BusinessException(50008, "无权操作该测试集", HttpStatus.FORBIDDEN);
        }
        testSuiteRepository.delete(suite);
    }

    @Transactional
    public Map<String, Object> run(String projectId, String suiteId, String targetUrl) {
        projectAccessService.assertProjectAccess(projectId);
        TestSuite suite = testSuiteRepository.findById(suiteId)
                .orElseThrow(() -> BusinessException.notFound("测试集不存在: " + suiteId));
        if (!projectId.equals(suite.getProjectId())) {
            throw new BusinessException(50008, "无权操作该测试集", HttpStatus.FORBIDDEN);
        }
        List<String> caseIds;
        try {
            caseIds = objectMapper.readValue(
                    suite.getCaseIds(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            caseIds = List.of();
        }
        if (caseIds.isEmpty()) {
            throw new BusinessException(50009, "测试集没有可用用例", HttpStatus.BAD_REQUEST);
        }
        String batchId = executionService.executeBatch(projectId, caseIds, targetUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);
        result.put("caseCount", caseIds.size());
        return result;
    }

    private Map<String, Object> toMap(TestSuite suite) {
        List<String> ids;
        try {
            ids = objectMapper.readValue(
                    suite.getCaseIds(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            ids = List.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", suite.getId());
        map.put("name", suite.getName());
        map.put("caseIds", ids);
        map.put("caseCount", ids.size());
        map.put("createdAt", suite.getCreatedAt());
        return map;
    }
}
