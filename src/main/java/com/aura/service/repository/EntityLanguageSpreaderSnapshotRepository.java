package com.aura.service.repository;

import com.aura.service.entity.EntityLanguageSpreaderSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EntityLanguageSpreaderSnapshotRepository extends JpaRepository<EntityLanguageSpreaderSnapshot, Long> {

    List<EntityLanguageSpreaderSnapshot> findByEntityId(Long entityId);

    Optional<EntityLanguageSpreaderSnapshot> findByEntityIdAndLanguageIgnoreCase(Long entityId, String language);

    // Used by the top-spreader-gap candidate to look up comparable movies' snapshots for the same
    // language this movie's own snapshot is being compared for.
    List<EntityLanguageSpreaderSnapshot> findByEntityIdInAndLanguageIgnoreCase(List<Long> entityIds, String language);
}
