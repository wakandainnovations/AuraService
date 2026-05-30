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

    List<ManagedEntity> findByCompetitorsId(Long competitorId);

    @Query("SELECT ek FROM ManagedEntity e JOIN e.keywords ek WHERE " +
           "(:language IS NULL OR ek.language = :language) AND " +
           "(:industry IS NULL OR ek.industry = :industry) AND " +
           "(:state IS NULL OR ek.state = :state) AND " +
           "(:genre IS NULL OR ek.genre = :genre) AND " +
           "(:entityId IS NULL OR e.id = :entityId)")
    List<EntityKeyword> findKeywordsByFilters(
            @Param("language") String language,
            @Param("industry") String industry,
            @Param("state") String state,
            @Param("genre") String genre,
            @Param("entityId") Long entityId);
}
