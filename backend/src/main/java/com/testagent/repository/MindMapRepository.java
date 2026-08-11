package com.testagent.repository;

import com.testagent.entity.MindMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MindMapRepository extends JpaRepository<MindMap, String> {

    // v3.9fix: 项目可能有多条脑图记录（多次生成），取最新一条
    Optional<MindMap> findFirstByProjectIdOrderByCreatedAtDesc(String projectId);

    List<MindMap> findAllByProjectId(String projectId);

    List<MindMap> findAllByOrderByCreatedAtDesc();
}
