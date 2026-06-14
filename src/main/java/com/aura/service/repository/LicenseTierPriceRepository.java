package com.aura.service.repository;

import com.aura.service.entity.LicenseTierPrice;
import com.aura.service.enums.LicenseTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LicenseTierPriceRepository extends JpaRepository<LicenseTierPrice, LicenseTier> {
}
