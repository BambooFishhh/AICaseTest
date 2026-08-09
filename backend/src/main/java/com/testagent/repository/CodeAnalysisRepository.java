package com.testagent.repository;

import com.testagent.entity.CodeAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CodeAnalysisRepository extends JpaRepository<CodeAnalysis, String> {

    Optional<CodeAnalysis> findByProjectId(String projectId);
}
