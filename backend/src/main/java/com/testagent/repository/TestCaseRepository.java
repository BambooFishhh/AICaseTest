package com.testagent.repository;

import com.testagent.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, String>, JpaSpecificationExecutor<TestCase> {

    List<TestCase> findByProjectId(String projectId);

    long countByProjectId(String projectId);

    List<TestCase> findByProjectIdAndType(String projectId, String type);

    List<TestCase> findByProjectIdAndModule(String projectId, String module);

    void deleteByProjectId(String projectId);
}
