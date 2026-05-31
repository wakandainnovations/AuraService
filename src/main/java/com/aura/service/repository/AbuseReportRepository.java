package com.aura.service.repository;

import com.aura.service.entity.AbuseReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {
}
