package com.testagent.repository;

import com.testagent.entity.ExecutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExecutionRecordRepository extends JpaRepository<ExecutionRecord, String> {
    List<ExecutionRecord> findByProjectIdOrderByStartTimeDesc(String projectId);

    /** v2.1: 按批次查询 */
    List<ExecutionRecord> findByBatchIdOrderByStartTimeAsc(String batchId);

    /** v2.4: 按批次查询（无排序） */
    List<ExecutionRecord> findByBatchId(String batchId);
}
