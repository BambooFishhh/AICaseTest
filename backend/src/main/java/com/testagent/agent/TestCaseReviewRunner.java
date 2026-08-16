package com.testagent.agent;

import com.testagent.service.TestCaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.Map;

// v5.12: 单条 AI 评审异步执行器，避免同步 LLM 调用撑爆前端/代理超时
@Component
public class TestCaseReviewRunner {

    private static final Logger log = LoggerFactory.getLogger(TestCaseReviewRunner.class);

    @Autowired
    private TestCaseService testCaseService;

    @Autowired
    @Qualifier("generationExecutor")
    private Executor generationExecutor;

    public Map<String, Object> startReview(String projectId, String testcaseId) {
        Map<String, Object> status = testCaseService.markReviewing(projectId, testcaseId);
        generationExecutor.execute(() -> {
            try {
                testCaseService.reviewTestCaseInternal(projectId, testcaseId);
            } catch (Exception e) {
                log.error("AI review failed for test case {} in project {}: {}",
                        testcaseId, projectId, e.getMessage());
                testCaseService.markReviewFailed(projectId, testcaseId, e.getMessage());
            }
        });
        return status;
    }
}
