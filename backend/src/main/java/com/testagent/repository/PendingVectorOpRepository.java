package com.testagent.repository;

import com.testagent.entity.PendingVectorOp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PendingVectorOpRepository extends JpaRepository<PendingVectorOp, String> {

    Optional<PendingVectorOp> findByCollectionAndExprAndStatus(String collection, String expr, String status);

    List<PendingVectorOp> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, LocalDateTime cutoff);
}
