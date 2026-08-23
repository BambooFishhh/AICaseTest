package com.testagent.repository;

import com.testagent.entity.TestCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestCaseRepository extends JpaRepository<TestCase, String>, JpaSpecificationExecutor<TestCase> {

    List<TestCase> findByProjectId(String projectId);

    long countByProjectId(String projectId);

    List<TestCase> findByProjectIdAndType(String projectId, String type);

    List<TestCase> findByProjectIdAndModule(String projectId, String module);

    void deleteByProjectId(String projectId);

    // v7.15(2a): 项目内展示序号分配——取当前项目最大 project_seq
    @org.springframework.data.jpa.repository.Query("select coalesce(max(t.projectSeq), 0) from TestCase t where t.projectId = :projectId")
    int findMaxProjectSeq(@Param("projectId") String projectId);
}
