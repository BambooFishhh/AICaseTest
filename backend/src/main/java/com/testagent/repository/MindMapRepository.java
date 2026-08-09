package com.testagent.repository;

import com.testagent.entity.MindMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MindMapRepository extends JpaRepository<MindMap, String> {

    Optional<MindMap> findByProjectId(String projectId);

    List<MindMap> findAllByOrderByCreatedAtDesc();
}
