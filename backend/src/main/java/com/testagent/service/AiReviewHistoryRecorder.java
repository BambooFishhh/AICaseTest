package com.testagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.testagent.entity.TestCase;
import com.testagent.entity.TestCaseAiReview;
import com.testagent.repository.TestCaseAiReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

// v5.12: 统一把 AI 评审结果写入历史表
@Component
public class AiReviewHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(AiReviewHistoryRecorder.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestCaseAiReviewRepository aiReviewRepository;

    public void record(TestCase tc, Map<String, Object> review, Map<String, Object> coverageRefs, String source) {
        if (tc == null || review == null) {
            return;
        }
        try {
            TestCaseAiReview row = new TestCaseAiReview();
            row.setId(UUID.randomUUID().toString().substring(0, 12));
            row.setProjectId(tc.getProjectId());
            row.setTestCaseId(tc.getId());
            row.setStatus(asString(review.get("status")));
            row.setIssues(toJson(review.getOrDefault("issues", java.util.List.of())));
            row.setSuggestedChanges(toJson(review.getOrDefault("suggestedChanges", Map.of())));
            row.setCoverageRefs(toJson(coverageRefs == null ? Map.of() : coverageRefs));
            Object confidence = review.get("confidence");
            row.setConfidence(confidence instanceof Number ? ((Number) confidence).doubleValue() : null);
            row.setSource(source == null || source.isBlank() ? "generation" : source);
            row.setCreatedAt(LocalDateTime.now());
            aiReviewRepository.save(row);
        } catch (Exception e) {
            log.warn("Failed to record AI review history for test case {}: {}", tc.getId(), e.getMessage());
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
