package com.aura.service.repository;

import com.aura.service.entity.CrisisPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CrisisPlanRepository extends JpaRepository<CrisisPlan, Long> {
}
