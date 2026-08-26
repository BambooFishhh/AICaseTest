package com.testagent.repository;

import com.testagent.entity.ReconciliationReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, String> {

    List<ReconciliationReport> findTop20ByOrderByCreatedAtDesc();
}
