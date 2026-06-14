package com.aura.service.repository;

import com.aura.service.entity.License;
import com.aura.service.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LicenseRepository extends JpaRepository<License, Long> {

    Optional<License> findByLicenseKey(String licenseKey);

    List<License> findByUser(User user);

    /** The single license a user is currently operating under (the invariant kept by issuing). */
    Optional<License> findByUserAndActiveTrue(User user);
}
