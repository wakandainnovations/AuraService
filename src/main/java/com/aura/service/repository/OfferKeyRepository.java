package com.aura.service.repository;

import com.aura.service.entity.OfferKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OfferKeyRepository extends JpaRepository<OfferKey, Long> {

    Optional<OfferKey> findByCode(String code);

    boolean existsByCode(String code);
}
