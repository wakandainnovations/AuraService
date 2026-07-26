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

    // ---- Per-tier usage counts (entity & keyword caps; see LicenseTier) ----

    /** How many entities the user owns — drives the entity cap and the usage meter. */
    long countByOwnerId(Long ownerId);

    /** Total keywords across all of the user's entities — drives the keyword cap and usage meter. */
    @Query("SELECT COUNT(ek) FROM ManagedEntity e JOIN e.keywords ek WHERE e.owner.id = :ownerId")
    long countKeywordsByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Keywords across all of the user's entities <em>except</em> {@code excludeEntityId}. Used by the
     * update-keywords cap check, where the edited entity's existing keywords are about to be replaced
     * and so must be excluded before adding the incoming count.
     */
    @Query("SELECT COUNT(ek) FROM ManagedEntity e JOIN e.keywords ek " +
           "WHERE e.owner.id = :ownerId AND e.id <> :excludeEntityId")
    long countKeywordsByOwnerIdExcludingEntity(@Param("ownerId") Long ownerId,
                                               @Param("excludeEntityId") Long excludeEntityId);

    // Legacy rows from before ownership existed; assigned to the seeded admin by the startup backfill.
    List<ManagedEntity> findByOwnerIsNull();

    // ---- Movie audience/budget-comparison lookups (see MovieAudienceServiceImpl) ----

    List<ManagedEntity> findByTypeAndLanguageIgnoreCase(String type, String language);

    List<ManagedEntity> findByTypeAndLanguageIgnoreCaseAndOwnerId(String type, String language, Long ownerId);

    List<ManagedEntity> findByTypeAndNameIgnoreCase(String type, String name);

    List<ManagedEntity> findByTypeAndNameIgnoreCaseAndOwnerId(String type, String name, Long ownerId);

    List<ManagedEntity> findByTypeAndNameIgnoreCaseAndLanguageIgnoreCase(String type, String name, String language);

    List<ManagedEntity> findByTypeAndNameIgnoreCaseAndLanguageIgnoreCaseAndOwnerId(
            String type, String name, String language, Long ownerId);

    // budget range is [min, max] inclusive; excludes the target movie itself via idNot.
    List<ManagedEntity> findByTypeAndBudgetBetweenAndIdNot(String type, Double min, Double max, Long idNot);

    List<ManagedEntity> findByTypeAndBudgetBetweenAndIdNotAndOwnerId(
            String type, Double min, Double max, Long idNot, Long ownerId);

    // language/industry/state are stored verbatim in whatever case the entity was
    // created with (e.g. "Kannada", "Tollywood"), but callers filter in any case, so
    // compare case-insensitively — same convention as the genre filter below. Only the
    // column is wrapped in LOWER(); the caller pre-lower-cases the bind value, because
    // Postgres can't infer the type of an arg inside lower(?) and errors on lower(bytea).
    @Query("SELECT ek FROM ManagedEntity e JOIN e.keywords ek WHERE " +
           "(:language IS NULL OR LOWER(ek.language) = :language) AND " +
           "(:industry IS NULL OR LOWER(ek.industry) = :industry) AND " +
           "(:state IS NULL OR LOWER(ek.state) = :state) AND " +
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
