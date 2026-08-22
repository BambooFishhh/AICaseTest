package com.testagent.repository;

import com.testagent.entity.AgentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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

    @Query("select t.status, count(t) from AgentTask t group by t.status")
    List<Object[]> countGroupByStatus();
}
