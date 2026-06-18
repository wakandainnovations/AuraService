package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityBasicInfo;
import com.aura.service.dto.EntityDetailResponse;
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
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityService {
    
    private final ManagedEntityRepository entityRepository;
    private final CheckpointRepository checkpointRepository;
    private final MentionRepository mentionRepository;
    private final EntityAccessService entityAccessService;
    private final LicenseService licenseService;

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

        return mapToDetailResponse(entity);
    }

    @Transactional
    public EntityDetailResponse updateEntity(String entityType, Long id, UpdateEntityRequest request) {
        ManagedEntity entity = entityAccessService.assertOwnedByCurrentUser(id);
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }

        entity.setName(request.getName());
        entity.setDirector(request.getDirector());
        entity.setActors(request.getActors());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            entity.setReleaseDate(request.getReleaseDate());
            entity.setIndustry(request.getIndustry());
            entity.setLanguage(resolveLanguage(request.getIndustry(), request.getLanguage()));
            entity.setGenre(joinGenres(request.getGenre()));
        } else if ("CELEBRITY".equalsIgnoreCase(entityType)) {
            entity.setIndustry(request.getIndustry());
        }
        // Stamp the keyword rows from the entity's own classification, so build
        // the keywords only after the fields above have been populated.
        entity.setKeywords(buildKeywordEntities(entity, request.getKeywords()));

        entity = entityRepository.save(entity);

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

        return mapToDetailResponse(entity);
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

    private EntityBasicInfo mapToBasicInfo(ManagedEntity entity) {
        EntityBasicInfo basicInfo = new EntityBasicInfo(entity.getId(), entity.getName(), entity.getType());
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
    
    private EntityDetailResponse mapToDetailResponse(ManagedEntity entity) {
        EntityDetailResponse response = new EntityDetailResponse();
        response.setId(entity.getId());
        response.setName(entity.getName());
        response.setType(entity.getType());
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
        } else if ("CELEBRITY".equalsIgnoreCase(entity.getType())) {
            response.setIndustry(entity.getIndustry());
        }
        return response;
    }
}
