package com.testagent.service;

import com.testagent.agent.TestCaseReviewAgent;
import com.testagent.entity.TestCase;
import com.testagent.repository.TestCaseAiReviewRepository;
import com.testagent.repository.TestCaseRepository;
import com.testagent.repository.TestCaseVersionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.6: 用例落库服务。重新生成时先删旧用例与版本快照，再统一写入，保证事务一致性。
 */
@Service
public class TestCasePersistenceService {

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestCaseVersionRepository testCaseVersionRepository;

    @Autowired
    private TestCaseAiReviewRepository aiReviewRepository;

    @Autowired
    private TestCaseReviewAgent testCaseReviewAgent;

    // v7.15(2a): 项目内展示序号分配器（与全局 id 双编号制）
    @Autowired
    private ProjectSeqAllocator projectSeqAllocator;

    @Transactional
    public List<TestCase> replaceAll(String projectId, List<TestCase> cases) {
        testCaseVersionRepository.deleteByProjectId(projectId);
        // v5.14fix: 重新生成时同步清理旧 AI 评审历史，避免与新评审记录混存
        aiReviewRepository.deleteByProjectId(projectId);
        testCaseRepository.deleteAll(testCaseRepository.findByProjectId(projectId));
        // v7.15(2a): 项目已清空，展示序号从 1 重新计数（先丢弃旧缓存）
        projectSeqAllocator.reset(projectId);
        for (TestCase tc : cases) {
            tc.setProjectId(projectId);
            tc.setProjectSeq(projectSeqAllocator.nextId(projectId));
            testCaseRepository.save(tc);
        }
        // v5.12: 项目归属确定后补记 AI 评审历史
        testCaseReviewAgent.recordHistoryForCases(cases, "generation");
        resequenceProjectSeq(projectId);
        return cases;
    }

    /**
     * v9.3: 按模块归组重编项目内展示序号——LLM 产出按覆盖端点走，同模块用例在编号轴上
     * 被打散，前端列表按 module 分组渲染时组内编号跳号（如 1,2,13,14），观感为"乱序"。
     * 重编规则：模块按首次出现排序，组内保持原相对顺序（原 projectSeq 升序），
     * 重编后每个模块组内编号连续。id 为稳定主键不受影响。
     */
    @Transactional
    public void resequenceProjectSeq(String projectId) {
        List<TestCase> all = testCaseRepository.findByProjectId(projectId);
        if (all == null || all.size() < 2) {
            return;
        }
        all.sort(Comparator.comparing(TestCase::getProjectSeq,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Map<String, List<TestCase>> byModule = new LinkedHashMap<>();
        for (TestCase tc : all) {
            String mod = (tc.getModule() == null || tc.getModule().isBlank())
                    ? "未分类" : tc.getModule();
            byModule.computeIfAbsent(mod, k -> new ArrayList<>()).add(tc);
        }
        int seq = 1;
        for (List<TestCase> group : byModule.values()) {
            for (TestCase tc : group) {
                tc.setProjectSeq(seq++);
            }
        }
        testCaseRepository.saveAll(all);
    }
}
