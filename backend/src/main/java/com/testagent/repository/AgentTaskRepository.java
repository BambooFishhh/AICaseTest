package com.testagent.repository;

import com.testagent.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, String>,
        JpaSpecificationExecutor<AgentTask> {

    Optional<AgentTask> findFirstByRequestIdAndTaskTypeOrderByCreatedAtDesc(
            String requestId, String taskType);

    List<AgentTask> findByStatusAndLeaseExpireAtBefore(String status, LocalDateTime cutoff);

    List<AgentTask> findByStatusAndStartedAtBefore(String status, LocalDateTime cutoff);

    List<AgentTask> findTop20ByStatusOrderByCreatedAtAsc(String status);

    /**
     * v6.8: CAS 抢占排队任务，防止多 worker/手动调度双执行。
     */
    @Modifying
    @Transactional
    @Query("UPDATE AgentTask t SET t.status = :status, t.phase = :phase, t.leaseOwner = :owner, "
            + "t.leaseExpireAt = :expireAt, t.heartbeatAt = :heartbeatAt, t.updatedAt = :updatedAt "
            + "WHERE t.id = :id AND t.status = 'QUEUED'")
    int claimQueued(@Param("id") String id, @Param("status") String status,
                    @Param("phase") String phase, @Param("owner") String owner,
                    @Param("expireAt") java.time.LocalDateTime expireAt,
                    @Param("heartbeatAt") java.time.LocalDateTime heartbeatAt,
                    @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Query("select t.status, count(t) from AgentTask t group by t.status")
    List<Object[]> countGroupByStatus();
}
