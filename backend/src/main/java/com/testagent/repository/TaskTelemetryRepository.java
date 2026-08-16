package com.testagent.repository;

import com.testagent.entity.TaskTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskTelemetryRepository extends JpaRepository<TaskTelemetry, String> {

    List<TaskTelemetry> findByTaskTypeOrderByCreatedAtDesc(String taskType);

    void deleteByProjectId(String projectId);
}
