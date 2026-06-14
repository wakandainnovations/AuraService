package com.aura.service.repository;

import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ManagedEntityRepository extends JpaRepository<ManagedEntity, Long> {
    List<ManagedEntity> findByType(String type);

    // Owner-scoped listing: a user only ever sees the entities they created.
    List<ManagedEntity> findByTypeAndOwnerId(String type, Long ownerId);

    List<ManagedEntity> findByCompetitorsId(Long competitorId);

    // Legacy rows from before ownership existed; assigned to the seeded admin by the startup backfill.
    List<ManagedEntity> findByOwnerIsNull();

    @Query("SELECT ek FROM ManagedEntity e JOIN e.keywords ek WHERE " +
           "(:language IS NULL OR ek.language = :language) AND " +
           "(:industry IS NULL OR ek.industry = :industry) AND " +
           "(:state IS NULL OR ek.state = :state) AND " +
           // genre is stored as a comma-separated list. The caller passes a pre-built
           // ',value,'-style pattern (see EntityKeyword genre handling) so we match a
           // whole token within the list; the pattern is only ever the right-hand side
           // of LIKE so the column's text type is inferred even when the value is null.
           // Genres are stored verbatim (e.g. "Drama") but callers filter in any case,
           // so compare case-insensitively — the caller lower-cases the pattern to match.
           "(:genrePattern IS NULL OR LOWER(CONCAT(',', ek.genre, ',')) LIKE :genrePattern) AND " +
           "(:entityId IS NULL OR e.id = :entityId)")
    List<EntityKeyword> findKeywordsByFilters(
            @Param("language") String language,
            @Param("industry") String industry,
            @Param("state") String state,
            @Param("genrePattern") String genrePattern,
            @Param("entityId") Long entityId);
}
