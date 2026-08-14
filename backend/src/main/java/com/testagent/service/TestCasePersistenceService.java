package com.testagent.service;

import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v5.6: 用例落库服务。重新生成时先删旧用例与版本快照，再统一写入，保证事务一致性。
 */
@Service
public class TestCasePersistenceService {

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;

    @Transactional
    public List<TestCase> replaceAll(String projectId, List<TestCase> cases) {
        testCaseVersionRepository.deleteByProjectId(projectId);
        testCaseRepository.deleteAll(testCaseRepository.findByProjectId(projectId));
        for (TestCase tc : cases) {
            tc.setProjectId(projectId);
            testCaseRepository.save(tc);
        }
        return cases;
    }
}
