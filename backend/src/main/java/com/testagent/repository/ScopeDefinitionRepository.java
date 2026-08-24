package com.testagent.repository;

import com.testagent.entity.ScopeDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScopeDefinitionRepository extends JpaRepository<ScopeDefinition, String> {

    List<ScopeDefinition> findByProjectIdOrderByCreatedAtDesc(String projectId);

    void deleteByProjectId(String projectId);
}
