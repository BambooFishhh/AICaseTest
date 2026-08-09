package com.testagent.repository;

import com.testagent.entity.ExecutionStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionStepRepository extends JpaRepository<ExecutionStep, String> {
    List<ExecutionStep> findByExecutionIdOrderByStepIndexAsc(String executionId);
}
