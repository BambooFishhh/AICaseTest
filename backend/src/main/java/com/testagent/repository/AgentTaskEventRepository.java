package com.testagent.repository;

import com.testagent.entity.AgentTaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentTaskEventRepository extends JpaRepository<AgentTaskEvent, String> {

    List<AgentTaskEvent> findByTaskIdOrderByCreatedAtAsc(String taskId);
}
