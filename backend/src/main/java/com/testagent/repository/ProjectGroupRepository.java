package com.testagent.repository;

import com.testagent.entity.ProjectGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectGroupRepository extends JpaRepository<ProjectGroup, String> {
    List<ProjectGroup> findAllByOrderByCreatedAtDesc();
}
