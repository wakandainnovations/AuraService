package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityBasicInfo;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.EntityImage;
import com.aura.service.dto.IndianMacroSnapshot;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.UpdateCompetitorsRequest;
import com.aura.service.dto.UpdateEntityRequest;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.dto.LicenseUsageResponse;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.enums.MovieIndustry;
import com.aura.service.exception.LimitException;
import com.aura.service.exception.ResourceNotFoundException;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityService {

    private final ManagedEntityRepository entityRepository;
    private final CheckpointRepository checkpointRepository;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;
    private final LicenseService licenseService;
    private final IndianMacroEconomicDataService macroEconomicDataService;
    private final EntityImageMatcher imageMatcher;
    private final CheckpointDefaultsService checkpointDefaultsService;

    @Value("${entity.images.base-path}")
    private String imagesBasePath;

    @Transactional
    public EntityDetailResponse createEntity(String entityType, CreateEntityRequest request) {
        // The creator owns the entity; everything below is scoped to this owner.
        User owner = entityAccessService.currentUser();

        // Reject before creating anything if the owner is already at their tier's entity cap.
        enforceEntityCap(owner.getId());

        ManagedEntity entity = new ManagedEntity();
        entity.setName(request.getName());
        entity.setType(entityType);
        entity.setOwner(owner);
        entity.setDirector(request.getDirector());
        entity.setActors(request.getActors());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            entity.setReleaseDate(request.getReleaseDate());
            entity.setIndustry(request.getIndustry());
            entity.setLanguage(resolveLanguage(request.getIndustry(), request.getLanguage()));
            entity.setGenre(joinGenres(request.getGenre()));
            entity.setSynopsis(request.getSynopsis());
            entity.setBudget(request.getBudget());
            entity.setProductionCompany(request.getProductionCompany());
            entity.setRuntime(request.getRuntime());
            applyReleaseDateDerivedFields(entity, request.getReleaseDate());
        } else if ("CELEBRITY".equalsIgnoreCase(entityType)) {
            entity.setIndustry(request.getIndustry());
        }
        // Stamp the keyword rows from the entity's own classification, so build
        // the keywords only after the fields above have been populated.
        List<EntityKeyword> keywords = buildKeywordEntities(entity, request.getKeywords());
        // A new entity has no existing keywords of its own, so the resulting total is every other
        // owned entity's keywords plus the incoming ones (exclude nothing — this entity isn't saved yet).
        enforceKeywordCap(owner.getId(), null, keywords.size());
        entity.setKeywords(keywords);

        entity = entityRepository.save(entity);
        // Attribute any mentions already collected for these keywords, so a brand-new entity sees its
        // keywords' history on the dashboards instead of an empty join table.
        resyncMentionLinks(entity.getId());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            checkpointDefaultsService.seedDefaults(entity);
        }

        return mapToDetailResponse(entity);
    }

    @Transactional
    public EntityDetailResponse updateEntity(String entityType, Long id, UpdateEntityRequest request) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }

        // A renamed entity's previously-matched poster (if any) almost certainly belongs to the old
        // name, so re-match against the images directory rather than leaving the stale file in place —
        // this is what let a renamed movie keep showing another movie's poster indefinitely.
        if (!java.util.Objects.equals(entity.getName(), request.getName())) {
            entity.setImagePath(imageMatcher.matchFile(request.getName()));
        }

        entity.setName(request.getName());
        entity.setDirector(request.getDirector());
        entity.setActors(request.getActors());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            entity.setReleaseDate(request.getReleaseDate());
            entity.setIndustry(request.getIndustry());
            entity.setLanguage(resolveLanguage(request.getIndustry(), request.getLanguage()));
            entity.setGenre(joinGenres(request.getGenre()));
            entity.setSynopsis(request.getSynopsis());
            entity.setBudget(request.getBudget());
            entity.setProductionCompany(request.getProductionCompany());
            entity.setRuntime(request.getRuntime());
            applyReleaseDateDerivedFields(entity, request.getReleaseDate());
        } else if ("CELEBRITY".equalsIgnoreCase(entityType)) {
            entity.setIndustry(request.getIndustry());
        }
        // Stamp the keyword rows from the entity's own classification, so build
        // the keywords only after the fields above have been populated.
        entity.setKeywords(buildKeywordEntities(entity, request.getKeywords()));

        entity = entityRepository.save(entity);
        // Keywords may have changed; re-derive this entity's mention links from the new keyword set.
        resyncMentionLinks(entity.getId());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            checkpointDefaultsService.recomputeReleaseDerivedStages(entity);
        }

        return mapToDetailResponse(entity);
    }

    public List<EntityBasicInfo> getAllEntities(String entityType, Long ownerId) {
        // resolveOwnerScope enforces the admin rules: non-admins are pinned to their own id (and
        // rejected if they pass ownerId); an admin gets the requested owner, or null for "all".
        Long scope = entityAccessService.resolveOwnerScope(ownerId);
        List<ManagedEntity> entities = scope == null
                ? entityRepository.findByType(entityType)
                : entityRepository.findByTypeAndOwnerId(entityType, scope);
        return entities.stream()
                .map(this::mapToBasicInfo)
                .collect(Collectors.toList());
    }

    public EntityDetailResponse getEntityById(String entityType, Long id) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }
        return mapToDetailResponse(entity);
    }
    
    @Transactional
    public EntityDetailResponse updateCompetitors(String entityType, Long id, UpdateCompetitorsRequest request) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }

        // Only the caller's own entities may be added as competitors — silently drop any id that
        // resolves to another user's entity (or doesn't exist), so competitor links can't leak existence.
        Long ownerId = entity.getOwner() == null ? null : entity.getOwner().getId();
        List<ManagedEntity> competitors = entityRepository.findAllById(request.getCompetitorIds()).stream()
                .filter(c -> c.getOwner() != null && c.getOwner().getId().equals(ownerId))
                .collect(Collectors.toList());
        entity.getCompetitors().addAll(competitors);
        
        entity = entityRepository.save(entity);
        
        return mapToDetailResponse(entity);
    }

    @Transactional
    public EntityDetailResponse updateKeywords(String entityType, Long id, UpdateKeywordsRequest request) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }

        List<EntityKeyword> keywords = buildKeywordEntities(entity, request.getKeywords());
        // Keywords are capped across ALL the owner's entities. This entity's existing keywords are
        // about to be replaced, so exclude them and add the incoming count for the resulting total.
        enforceKeywordCap(ownerIdOf(entity), entity.getId(), keywords.size());
        entity.setKeywords(keywords);

        entity = entityRepository.save(entity);
        // Re-derive this entity's mention links so newly added keywords pick up their existing history
        // and any removed keywords' posts are dropped.
        resyncMentionLinks(entity.getId());

        return mapToDetailResponse(entity);
    }

    /**
     * Reads the poster image file for an entity off disk, resolved against the configured
     * {@code entity.images.base-path}. 404s (via {@link ResourceNotFoundException}) if the entity has
     * no matched image yet, or if the resolved file is missing — including when it would resolve
     * outside the configured directory, since {@code imagePath} is only ever a bare filename.
     */
    public EntityImage getEntityImage(String entityType, Long id) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new ResourceNotFoundException("Entity with id " + id + " is not of type " + entityType);
        }
        if (entity.getImagePath() == null) {
            throw new ResourceNotFoundException("Entity with id " + id + " has no image");
        }

        Path baseDir = Path.of(imagesBasePath).normalize();
        Path imageFile = baseDir.resolve(entity.getImagePath()).normalize();
        if (!imageFile.startsWith(baseDir) || !Files.isRegularFile(imageFile)) {
            throw new ResourceNotFoundException("Image file for entity " + id + " is missing");
        }

        try {
            return new EntityImage(Files.readAllBytes(imageFile), contentTypeFor(entity.getImagePath()));
        } catch (IOException e) {
            throw new ResourceNotFoundException("Failed to read image file for entity " + id);
        }
    }

    private String contentTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    @Transactional
    public void deleteEntity(String entityType, Long id) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }

        // Detach this entity from any other entity that lists it as a competitor,
        // otherwise the entity_competitors foreign key would block the delete.
        List<ManagedEntity> referencingEntities = entityRepository.findByCompetitorsId(id);
        for (ManagedEntity referencing : referencingEntities) {
            referencing.getCompetitors().removeIf(competitor -> competitor.getId().equals(id));
        }
        entityRepository.saveAll(referencingEntities);

        // Remove dependent checkpoints (managed_entity_id is a non-null foreign key).
        checkpointRepository.deleteByManagedEntityId(id);

        // Detach this entity from its mentions (a post may be shared with other entities), then purge
        // any mention left with no entity links, otherwise the join-table FK blocks the delete.
        mentionRepository.unlinkEntityFromMentions(id);
        mentionRepository.deleteMentionsWithNoEntities();

        entityRepository.delete(entity);
    }

    /**
     * The authenticated user's current usage against their tier's caps — entity and keyword counts vs
     * their maxima. Read-only and <strong>price-free</strong>: built from {@link LicenseTier} limits
     * (never pricing), so it is safe to surface to regular users for usage meters.
     */
    public LicenseUsageResponse currentUsage() {
        User user = entityAccessService.currentUser();
        // Use the effective tier so the caps shown in the meter match what enforcement actually
        // allows when an offer-key override is in effect.
        LicenseTier tier = licenseService.effectiveTier();
        long entitiesUsed = entityRepository.countByOwnerId(user.getId());
        long keywordsUsed = entityRepository.countKeywordsByOwnerId(user.getId());
        return new LicenseUsageResponse(
                entitiesUsed, tier.getMaxEntities(),
                keywordsUsed, tier.getMaxKeywords());
    }

    /** Rejects (409 ENTITIES) when the owner already holds the maximum entities their tier allows. */
    private void enforceEntityCap(Long ownerId) {
        int limit = licenseService.currentMaxEntities();
        long current = entityRepository.countByOwnerId(ownerId);
        if (current >= limit) {
            throw new LimitException(LimitException.LimitType.ENTITIES, limit, (int) current);
        }
    }

    /**
     * Rejects (409 KEYWORDS) when an operation would push the owner's total keyword count — summed
     * across every entity they own — past their tier's cap. {@code excludeEntityId} is the entity
     * whose keywords are being replaced (so its current keywords don't double-count); pass null on
     * create. {@code incomingKeywordCount} is the number of keywords the operation will add for it.
     */
    private void enforceKeywordCap(Long ownerId, Long excludeEntityId, int incomingKeywordCount) {
        int limit = licenseService.currentMaxKeywords();
        long others = excludeEntityId == null
                ? entityRepository.countKeywordsByOwnerId(ownerId)
                : entityRepository.countKeywordsByOwnerIdExcludingEntity(ownerId, excludeEntityId);
        long resulting = others + incomingKeywordCount;
        if (resulting > limit) {
            throw new LimitException(LimitException.LimitType.KEYWORDS, limit, (int) resulting);
        }
    }

    private Long ownerIdOf(ManagedEntity entity) {
        return entity.getOwner() == null ? null : entity.getOwner().getId();
    }

    /**
     * Re-derives an entity's {@code mention_entities} links from its current keywords: links any
     * already-collected mention whose content matches a keyword and drops links to mentions that no
     * longer match. Mentions are attributed to an entity purely by keyword-content match (the same rule
     * the dashboard queries use), so an entity created — or re-keyworded — after its keywords' mentions
     * were collected still shows their history rather than an empty dashboard. Must run after the
     * entity's keyword rows are persisted (the repository statements flush before reading them).
     */
    private void resyncMentionLinks(Long entityId) {
        mentionRepository.unlinkStaleMentionsByKeyword(entityId);
        mentionRepository.linkExistingMentionsByKeyword(entityId);
    }

    /**
     * Builds the {@code entity_keywords} rows for an entity. The keyword text comes
     * from the request, but the classification columns are derived from the entity
     * itself so they stay consistent and the marketing/aggregation filters (which
     * match on these columns) work: {@code category} from the entity type
     * ({@code media.movie}/{@code media.celebrity}), and {@code language}/{@code industry}/
     * {@code genre} from the entity's own fields. A movie's multiple genres are stored on
     * a single row as the entity's comma-separated {@code genre} value; readers of the
     * column split it back into individual genres.
     */
    private List<EntityKeyword> buildKeywordEntities(ManagedEntity entity, List<KeywordDto> dtos) {
        if (dtos == null) {
            return new ArrayList<>();
        }
        String category = categoryForType(entity.getType());
        String language = entity.getLanguage();
        String industry = entity.getIndustry();
        String genre = entity.getGenre();

        List<EntityKeyword> keywords = new ArrayList<>();
        for (KeywordDto dto : dtos) {
            if (dto == null || dto.getKeyword() == null || dto.getKeyword().isBlank()) {
                continue;
            }
            keywords.add(new EntityKeyword(dto.getKeyword().trim(), category, language, null, industry, genre));
        }
        return keywords;
    }

    private String categoryForType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase()) {
            case "MOVIE" -> "media.movie";
            case "CELEBRITY" -> "media.celebrity";
            default -> null;
        };
    }

    private List<KeywordDto> toKeywordDtos(List<EntityKeyword> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream()
                .map(k -> new KeywordDto(
                        k.getKeyword(),
                        k.getCategory(),
                        k.getLanguage(),
                        k.getState(),
                        k.getIndustry(),
                        k.getGenre()))
                .collect(Collectors.toList());
    }

    /**
     * Keeps a movie's language consistent with its industry: a recognized regional
     * industry (Sandalwood, Bollywood, Tollywood, Kollywood, Mollywood) dictates the
     * language, overriding whatever the client supplied. For any other industry
     * (e.g. Hollywood) the client-supplied language is kept.
     */
    private String resolveLanguage(String industry, String requestedLanguage) {
        String derived = MovieIndustry.languageFor(industry);
        return derived != null ? derived : requestedLanguage;
    }

    /**
     * Derives {@code releaseDay} (day of week) and India's {@code gdpUsdBillions}/
     * {@code inflationRatePct} for the release year, purely from {@code releaseDate} — none of
     * these are client-supplied, so they can never drift out of sync with the date they describe.
     * A null {@code releaseDate} clears all three, since there is nothing to derive them from.
     */
    private void applyReleaseDateDerivedFields(ManagedEntity entity, LocalDate releaseDate) {
        if (releaseDate == null) {
            entity.setReleaseDay(null);
            entity.setGdpUsdBillions(null);
            entity.setInflationRatePct(null);
            return;
        }

        entity.setReleaseDay(releaseDate.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));

        IndianMacroSnapshot macroSnapshot = macroEconomicDataService.lookup(releaseDate);
        entity.setGdpUsdBillions(macroSnapshot == null ? null : macroSnapshot.gdpUsdBillions());
        entity.setInflationRatePct(macroSnapshot == null ? null : macroSnapshot.inflationRatePct());
    }

    private String joinGenres(List<String> genres) {
        if (genres == null || genres.isEmpty()) {
            return null;
        }
        return genres.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(","));
    }

    private List<String> splitGenres(String genre) {
        if (genre == null || genre.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(genre.split(","))
                .map(String::trim)
                .filter(g -> !g.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * The URL the frontend fetches this entity's poster from (see EntityController's
     * {@code /image} endpoint), or null if no image has been matched for it yet. Built here rather
     * than stored, so it always reflects the entity's current id/type/imagePath.
     */
    private String imageUrlFor(ManagedEntity entity) {
        if (entity.getImagePath() == null) {
            return null;
        }
        return "/entities/" + entity.getType().toLowerCase() + "/" + entity.getId() + "/image";
    }

    private EntityBasicInfo mapToBasicInfo(ManagedEntity entity) {
        EntityBasicInfo basicInfo = new EntityBasicInfo(entity.getId(), entity.getName(), entity.getType());
        basicInfo.setImageUrl(imageUrlFor(entity));
        if ("MOVIE".equalsIgnoreCase(entity.getType())) {
            basicInfo.setDirector(entity.getDirector());
            basicInfo.setReleaseDate(entity.getReleaseDate());
            basicInfo.setLanguage(entity.getLanguage());
            basicInfo.setIndustry(entity.getIndustry());
            basicInfo.setGenre(splitGenres(entity.getGenre()));
        } else if ("CELEBRITY".equalsIgnoreCase(entity.getType())) {
            basicInfo.setIndustry(entity.getIndustry());
        }
        return basicInfo;
    }
    
    /**
     * Package-private (not {@code private}) so trusted in-package background callers that have
     * already resolved a {@link ManagedEntity} directly (e.g. {@code EntityMarketingReportService}'s
     * scheduled cache refresh, which has no authenticated request to scope ownership against) can
     * reuse this mapping without going through the owner-scoped {@link #getEntityById}.
     */
    EntityDetailResponse mapToDetailResponse(ManagedEntity entity) {
        EntityDetailResponse response = new EntityDetailResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setType(entity.getType());
        response.setOwnerId(ownerIdOf(entity));
        response.setImageUrl(imageUrlFor(entity));
        response.setImagePath(entity.getImagePath());
        response.setDirector(entity.getDirector());
        response.setActors(entity.getActors());
        response.setKeywords(toKeywordDtos(entity.getKeywords()));
        response.setCompetitors(
                entity.getCompetitors().stream()
                        .map(this::mapToBasicInfo)
                        .collect(Collectors.toList())
        );
        if ("MOVIE".equalsIgnoreCase(entity.getType())) {
            response.setReleaseDate(entity.getReleaseDate());
            response.setLanguage(entity.getLanguage());
            response.setIndustry(entity.getIndustry());
            response.setGenre(splitGenres(entity.getGenre()));
            response.setSynopsis(entity.getSynopsis());
            response.setBudget(entity.getBudget());
            response.setProductionCompany(entity.getProductionCompany());
            response.setRuntime(entity.getRuntime());
            response.setReleaseDay(entity.getReleaseDay());
            response.setGdpUsdBillions(entity.getGdpUsdBillions());
            response.setInflationRatePct(entity.getInflationRatePct());
        } else if ("CELEBRITY".equalsIgnoreCase(entity.getType())) {
            response.setIndustry(entity.getIndustry());
        }
        return response;
    }
}
