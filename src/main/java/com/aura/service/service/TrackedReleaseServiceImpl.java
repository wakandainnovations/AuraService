package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.CreateTrackedReleaseRequest;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.PlatformEntityRef;
import com.aura.service.dto.TrackedReleaseResponse;
import com.aura.service.dto.UpdateTrackedReleaseRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.TrackedRelease;
import com.aura.service.entity.User;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackedReleaseServiceImpl implements TrackedReleaseService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String POSTER_BASE = "https://image.tmdb.org/t/p/w92";

    /** Platform key in the response -> collector table (all keyed by an `entity` column). */
    static final Map<String, String> USAGE_TABLES = new LinkedHashMap<>();
    static {
        USAGE_TABLES.put("x", "x_posts");
        USAGE_TABLES.put("instagram", "instagram_posts");
        USAGE_TABLES.put("reddit", "reddit_posts");
        USAGE_TABLES.put("youtubeComments", "youtube_comments");
        USAGE_TABLES.put("youtubeVideos", "youtube_videos");
    }

    private final TrackedReleaseRepository repository;
    private final EntityAccessService entityAccessService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ManagedEntityRepository managedEntityRepository;
    private final UserRepository userRepository;
    private final EntityService entityService;

    /** Collector language (lower-case) -> this platform's film industry label. */
    static final Map<String, String> INDUSTRY_BY_LANGUAGE = Map.of(
            "tamil", "Kollywood", "telugu", "Tollywood", "kannada", "Sandalwood",
            "malayalam", "Mollywood", "hindi", "Bollywood");

    // Must match AuraSocialMediaService's tracking.days.before/after (it computes the window for TMDB rows).
    @Value("${tracked-releases.window-days-before:7}")
    private int daysBefore = 7;

    @Value("${tracked-releases.window-days-after:7}")
    private int daysAfter = 7;

    private Clock clock = Clock.system(IST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<TrackedReleaseResponse> list(String scope) {
        requireAdmin();
        LocalDate today = LocalDate.now(clock.withZone(IST));
        String s = scope == null ? "current" : scope.toLowerCase(Locale.ROOT);
        List<TrackedRelease> rows = repository.findAllByOrderByReleaseDateAscEntityNameAsc().stream()
                .filter(r -> switch (s) {
                    case "all" -> true;
                    case "excluded" -> r.isExcluded();
                    case "ended" -> !r.isExcluded() && r.getTrackUntil().isBefore(today);
                    case "current" -> !r.isExcluded() && !r.getTrackUntil().isBefore(today);
                    default -> throw new IllegalArgumentException("Unknown scope '" + scope + "' (use current, excluded, ended or all)");
                })
                .toList();
        Map<String, Map<String, Long>> usage = usageByEntity(rows.stream().map(TrackedRelease::getEntityName).toList());
        Map<String, List<PlatformEntityRef>> platform = platformEntitiesByName();
        return rows.stream().map(r -> withPlatform(toResponse(r, today, usage.get(r.getEntityName())), platform)).toList();
    }

    @Override
    @Transactional
    public TrackedReleaseResponse create(CreateTrackedReleaseRequest request) {
        requireAdmin();
        String title = request.getTitle().trim();
        if (repository.existsByEntityNameIgnoreCase(title)) {
            throw new IllegalArgumentException("'" + title + "' is already tracked");
        }
        TrackedRelease r = new TrackedRelease();
        r.setEntityName(title);
        r.setTitle(title);
        r.setLanguage(blankToNull(request.getLanguage()) == null ? null : request.getLanguage().trim().toLowerCase(Locale.ROOT));
        r.setSource(TrackedRelease.SOURCE_MANUAL);
        applyReleaseDate(r, request.getReleaseDate());
        List<String> keywords = normalizeKeywords(request.getKeywords());
        r.setKeywords(String.join(",", keywords.isEmpty() ? defaultKeywords(title) : keywords));
        TrackedRelease saved = repository.save(r);
        return toResponse(saved, LocalDate.now(clock.withZone(IST)), Map.of());
    }

    @Override
    @Transactional
    public TrackedReleaseResponse update(Long id, UpdateTrackedReleaseRequest request) {
        requireAdmin();
        TrackedRelease r = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tracked release not found: " + id));
        if (request.getKeywords() != null) {
            List<String> keywords = normalizeKeywords(request.getKeywords());
            if (keywords.isEmpty()) {
                throw new IllegalArgumentException("At least one keyword is required (exclude the movie to stop tracking it)");
            }
            r.setKeywords(String.join(",", keywords));
        }
        if (request.getExcluded() != null && request.getExcluded() != r.isExcluded()) {
            r.setExcluded(request.getExcluded());
            r.setExcludedAt(request.getExcluded() ? Instant.now(clock) : null);
            r.setExcludedReason(request.getExcluded() ? blankToNull(request.getExcludedReason()) : null);
            if (!request.getExcluded()) {
                // An admin decided this movie is worth tracking: the collector's relevance check
                // must not auto-exclude it again (and it may scan on Apify right away).
                r.setRelevanceOverride(true);
            }
        } else if (r.isExcluded() && request.getExcludedReason() != null) {
            r.setExcludedReason(blankToNull(request.getExcludedReason()));
        }
        if (request.getReleaseDate() != null && !request.getReleaseDate().equals(r.getReleaseDate())) {
            if (!TrackedRelease.SOURCE_MANUAL.equals(r.getSource())) {
                throw new IllegalArgumentException("Release date of a TMDB release comes from TMDB and can't be edited here");
            }
            applyReleaseDate(r, request.getReleaseDate());
        }
        TrackedRelease saved = repository.save(r);
        Map<String, Long> usage = usageByEntity(List.of(saved.getEntityName())).get(saved.getEntityName());
        return toResponse(saved, LocalDate.now(clock.withZone(IST)), usage);
    }

    @Override
    @Transactional
    public TrackedReleaseResponse addToPlatform(Long id, Long ownerId) {
        requireAdmin();
        TrackedRelease r = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tracked release not found: " + id));
        User owner = ownerId == null ? entityAccessService.currentUser()
                : userRepository.findById(ownerId)
                        .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        if (!managedEntityRepository.findByTypeAndNameIgnoreCaseAndOwnerId("MOVIE", r.getEntityName(), owner.getId()).isEmpty()) {
            throw new IllegalArgumentException("'" + r.getEntityName() + "' is already on the platform for " + owner.getUsername());
        }
        entityService.createEntityForOwner(owner, "MOVIE", toCreateEntityRequest(r));
        Map<String, List<PlatformEntityRef>> platform = platformEntitiesByName();
        Map<String, Long> usage = usageByEntity(List.of(r.getEntityName())).get(r.getEntityName());
        return withPlatform(toResponse(r, LocalDate.now(clock.withZone(IST)), usage), platform);
    }

    /**
     * Entity fields from the tracked release. The name is kept exactly (collected posts are stored
     * under it); language/industry use this platform's labels ("Telugu" / "Tollywood").
     */
    static CreateEntityRequest toCreateEntityRequest(TrackedRelease r) {
        CreateEntityRequest req = new CreateEntityRequest();
        req.setName(r.getEntityName());
        req.setReleaseDate(r.getReleaseDate());
        String lang = r.getLanguage() == null ? null : r.getLanguage().toLowerCase(Locale.ROOT);
        if (lang != null) {
            req.setLanguage(Character.toUpperCase(lang.charAt(0)) + lang.substring(1));
            req.setIndustry(INDUSTRY_BY_LANGUAGE.get(lang));
        }
        List<KeywordDto> keywords = new ArrayList<>();
        for (String k : splitKeywords(r.getKeywords())) {
            KeywordDto dto = new KeywordDto();
            dto.setKeyword(k);
            keywords.add(dto);
        }
        req.setKeywords(keywords);
        return req;
    }

    private Map<String, List<PlatformEntityRef>> platformEntitiesByName() {
        Map<String, List<PlatformEntityRef>> byName = new HashMap<>();
        for (ManagedEntity e : managedEntityRepository.findByType("MOVIE")) {
            if (e.getName() == null) continue;
            User o = e.getOwner();
            byName.computeIfAbsent(e.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(new PlatformEntityRef(e.getId(), o == null ? null : o.getId(), o == null ? null : o.getUsername()));
        }
        return byName;
    }

    private static TrackedReleaseResponse withPlatform(TrackedReleaseResponse resp, Map<String, List<PlatformEntityRef>> platform) {
        resp.setPlatformEntities(platform.getOrDefault(resp.getEntityName().toLowerCase(Locale.ROOT), List.of()));
        return resp;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        requireAdmin();
        TrackedRelease r = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tracked release not found: " + id));
        if (!TrackedRelease.SOURCE_MANUAL.equals(r.getSource())) {
            throw new IllegalArgumentException("TMDB releases can't be deleted (discovery would add them back) - exclude it instead");
        }
        repository.delete(r);
    }

    private void applyReleaseDate(TrackedRelease r, LocalDate releaseDate) {
        r.setReleaseDate(releaseDate);
        r.setTrackFrom(releaseDate.minusDays(daysBefore));
        r.setTrackUntil(releaseDate.plusDays(daysAfter));
    }

    /**
     * Collected-item counts per entity and platform. Each table is queried separately and a
     * failure (e.g. a collector table not created yet in this environment) just leaves that
     * platform out instead of failing the whole admin page.
     */
    Map<String, Map<String, Long>> usageByEntity(List<String> entityNames) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        if (entityNames.isEmpty()) return result;
        Map<String, Object> params = Map.of("names", entityNames);
        USAGE_TABLES.forEach((platform, table) -> {
            try {
                // Table names come from the fixed USAGE_TABLES map above, never from input.
                jdbc.query("SELECT entity, COUNT(*) AS n FROM " + table + " WHERE entity IN (:names) GROUP BY entity",
                        params, rs -> {
                            result.computeIfAbsent(rs.getString("entity"), k -> new LinkedHashMap<>())
                                    .put(platform, rs.getLong("n"));
                        });
            } catch (Exception e) {
                log.warn("Tracked-release usage count unavailable for {}: {}", table, e.getMessage());
            }
        });
        return result;
    }

    private TrackedReleaseResponse toResponse(TrackedRelease r, LocalDate today, Map<String, Long> usage) {
        Map<String, Long> collected = new LinkedHashMap<>();
        USAGE_TABLES.keySet().forEach(p -> collected.put(p, usage == null ? 0L : usage.getOrDefault(p, 0L)));
        return TrackedReleaseResponse.builder()
                .id(r.getId())
                .tmdbId(r.getTmdbId())
                .entityName(r.getEntityName())
                .title(r.getTitle())
                .originalTitle(r.getOriginalTitle())
                .language(r.getLanguage())
                .releaseDate(r.getReleaseDate())
                .trackFrom(r.getTrackFrom())
                .trackUntil(r.getTrackUntil())
                .keywords(splitKeywords(r.getKeywords()))
                .source(r.getSource())
                .popularity(r.getPopularity())
                .posterUrl(r.getPosterPath() == null ? null : POSTER_BASE + r.getPosterPath())
                .excluded(r.isExcluded())
                .excludedReason(r.getExcludedReason())
                .excludedAt(r.getExcludedAt())
                .relevancePct(r.getRelevancePct())
                .relevanceSample(r.getRelevanceSample())
                .relevanceChecked(r.getRelevanceCheckedAt() != null)
                .relevanceOverride(r.isRelevanceOverride())
                .status(status(r, today))
                .collected(collected)
                .collectedTotal(collected.values().stream().mapToLong(Long::longValue).sum())
                .build();
    }

    static String status(TrackedRelease r, LocalDate today) {
        if (r.isExcluded()) return "EXCLUDED";
        if (today.isBefore(r.getTrackFrom())) return "UPCOMING";
        if (today.isAfter(r.getTrackUntil())) return "ENDED";
        return "TRACKING";
    }

    /** Trims, drops blanks, splits on commas (the storage separator) and de-dupes case-insensitively. */
    static List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) return List.of();
        Map<String, String> byLower = new LinkedHashMap<>();
        keywords.stream().filter(Objects::nonNull)
                .flatMap(k -> Arrays.stream(k.split(",")))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .forEach(k -> byLower.putIfAbsent(k.toLowerCase(Locale.ROOT), k));
        return new ArrayList<>(byLower.values());
    }

    static List<String> splitKeywords(String keywords) {
        return keywords == null ? List.of() : normalizeKeywords(List.of(keywords));
    }

    /** Same defaults as the collector's discovery: hashtag form, plus the phrase when multi-word. */
    static List<String> defaultKeywords(String title) {
        String compact = title.replaceAll("[^\\p{L}\\p{N}]", "");
        String phrase = title.replaceAll("[^\\p{L}\\p{N}\\s.'&-]", " ").replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        if (!compact.isEmpty()) out.add(compact);
        if (phrase.contains(" ")) out.add(phrase);
        return out.isEmpty() ? List.of(title.trim()) : out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void requireAdmin() {
        if (!entityAccessService.currentUserIsAdmin()) {
            throw new AccessDeniedException("Tracked releases are restricted to administrators");
        }
    }
}
