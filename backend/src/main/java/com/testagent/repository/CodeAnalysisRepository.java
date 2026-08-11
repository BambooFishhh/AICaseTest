package com.testagent.repository;

import com.testagent.entity.CodeAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeAnalysisRepository extends JpaRepository<CodeAnalysis, String> {

    // v3.9fix: 项目可能有多条分析记录（多次分析），取最新一条
    Optional<CodeAnalysis> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);

    List<CodeAnalysis> findAllByProjectId(String projectId);
}
