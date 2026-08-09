package com.testagent.repository;

import com.testagent.entity.ExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, String> {
    List<ExecutionRecord> findByProjectIdOrderByStartTimeDesc(String projectId);
}
