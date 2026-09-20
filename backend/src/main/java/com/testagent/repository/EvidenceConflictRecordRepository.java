package com.testagent.repository;

import com.testagent.entity.EvidenceConflictRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * v13.17(证据权威判定): 冲突记录仓储。
 */
@Repository
public interface EvidenceConflictRecordRepository extends JpaRepository<EvidenceConflictRecord, String> {

    /** 项目下全部冲突（新→旧），供前端待裁决清单 */
    List<EvidenceConflictRecord> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** 按裁决状态过滤（如只取 pending 的待裁决项） */
    List<EvidenceConflictRecord> findByProjectIdAndVerdictOrderByCreatedAtDesc(String projectId, String verdict);

    long countByProjectIdAndVerdict(String projectId, String verdict);

    void deleteByProjectId(String projectId);
}
