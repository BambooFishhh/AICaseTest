package com.testagent.repository;

import com.testagent.entity.StateMachine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StateMachineRepository extends JpaRepository<StateMachine, String> {

    List<StateMachine> findByProjectId(String projectId);

    void deleteByProjectId(String projectId);
}
