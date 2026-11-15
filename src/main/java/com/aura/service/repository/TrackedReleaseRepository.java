package com.aura.service.repository;

import com.aura.service.entity.TrackedRelease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrackedReleaseRepository extends JpaRepository<TrackedRelease, Long> {

    boolean existsByEntityNameIgnoreCase(String entityName);

    List<TrackedRelease> findAllByOrderByReleaseDateAscEntityNameAsc();
}
