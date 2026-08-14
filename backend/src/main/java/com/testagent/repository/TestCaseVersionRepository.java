package com.testagent.repository;

import com.testagent.entity.TestCaseVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TestCaseVersionRepository extends JpaRepository<TestCaseVersion, String> {

    List<TestCaseVersion> findByTestCaseIdOrderByVersionNoDesc(String testCaseId);

    long countByTestCaseId(String testCaseId);

    Optional<TestCaseVersion> findByIdAndTestCaseId(String id, String testCaseId);

    // v5.6: 生命周期清理
    void deleteByTestCaseId(String testCaseId);

    void deleteByProjectId(String projectId);
}
