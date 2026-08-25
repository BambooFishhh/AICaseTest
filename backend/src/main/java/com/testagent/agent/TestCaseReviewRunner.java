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
        try {
            generationExecutor.execute(() -> {
                try {
                    testCaseService.reviewTestCaseInternal(projectId, testcaseId);
                } catch (Exception e) {
                    log.error("AI review failed for test case {} in project {}: {}",
                            testcaseId, projectId, e.getMessage());
                    testCaseService.markReviewFailed(projectId, testcaseId, e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // v8.4fix: 生成线程池队列满时快速失败，避免用例卡在 reviewing 状态
            log.warn("AI review 提交被拒绝（线程池满）: project={}, case={}", projectId, testcaseId);
            testCaseService.markReviewFailed(projectId, testcaseId, "评审任务队列已满，请稍后重试");
        }
        return status;
    }
}
