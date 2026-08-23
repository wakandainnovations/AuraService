package com.aura.service.repository;

import com.aura.service.entity.TopSpreaderInsightsCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TopSpreaderInsightsCacheRepository extends JpaRepository<TopSpreaderInsightsCache, Long> {
    Optional<TopSpreaderInsightsCache> findByEntityIdAndLanguageAndSpreaderLimitAndPostsPerSpreader(
            Long entityId, String language, int spreaderLimit, int postsPerSpreader);
}
