package com.testagent.repository;

import com.testagent.entity.TestCaseAiReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestCaseAiReviewRepository extends JpaRepository<TestCaseAiReview, String> {

    List<TestCaseAiReview> findByTestCaseIdOrderByCreatedAtDesc(String testCaseId);

    Optional<TestCaseAiReview> findFirstByTestCaseIdOrderByCreatedAtDesc(String testCaseId);

    // v5.12: 生命周期清理
    void deleteByTestCaseId(String testCaseId);

    void deleteByProjectId(String projectId);
}
