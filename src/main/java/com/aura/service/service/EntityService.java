package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityBasicInfo;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.UpdateCompetitorsRequest;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.entity.EntityKeyword;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntityService {
    
    private final ManagedEntityRepository entityRepository;
    private final CheckpointRepository checkpointRepository;

    @Transactional
    public EntityDetailResponse createEntity(String entityType, CreateEntityRequest request) {
        ManagedEntity entity = new ManagedEntity();
        entity.setName(request.getName());
        entity.setType(entityType);
        entity.setDirector(request.getDirector());
        entity.setActors(request.getActors());
        entity.setKeywords(toKeywordEntities(request.getKeywords()));
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            entity.setReleaseDate(request.getReleaseDate());
            entity.setIndustry(request.getIndustry());
            entity.setGenre(joinGenres(request.getGenre()));
        }
        
        entity = entityRepository.save(entity);
        
        return mapToDetailResponse(entity);
    }
    
    public List<EntityBasicInfo> getAllEntities(String entityType) {
        return entityRepository.findByType(entityType).stream()
                .map(this::mapToBasicInfo)
                .collect(Collectors.toList());
    }
    
    public EntityDetailResponse getEntityById(String entityType, Long id) {
        ManagedEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + id));
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }
        return mapToDetailResponse(entity);
    }
    
    @Transactional
    public EntityDetailResponse updateCompetitors(String entityType, Long id, UpdateCompetitorsRequest request) {
        ManagedEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + id));
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }
        
        List<ManagedEntity> competitors = entityRepository.findAllById(request.getCompetitorIds());
        entity.getCompetitors().addAll(competitors);
        
        entity = entityRepository.save(entity);
        
        return mapToDetailResponse(entity);
    }

    @Transactional
    public EntityDetailResponse updateKeywords(String entityType, Long id, UpdateKeywordsRequest request) {
        ManagedEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + id));
        if (!entity.getType().equalsIgnoreCase(entityType)) {
            throw new RuntimeException("Entity with id " + id + " is not of type " + entityType);
        }
        
        entity.setKeywords(toKeywordEntities(request.getKeywords()));
        
        entity = entityRepository.save(entity);
        
        return mapToDetailResponse(entity);
    }
    
    @Transactional
    public void deleteEntity(String entityType, Long id) {
        ManagedEntity entity = entityRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Entity not found with id: " + id));
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

        entityRepository.delete(entity);
    }

    private List<EntityKeyword> toKeywordEntities(List<KeywordDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        return dtos.stream()
                .map(this::toKeywordEntity)
                .collect(Collectors.toList());
    }

    private EntityKeyword toKeywordEntity(KeywordDto dto) {
        validateKeyword(dto);
        return new EntityKeyword(
                dto.getKeyword(),
                dto.getCategory(),
                dto.getLanguage(),
                dto.getState(),
                dto.getIndustry(),
                dto.getGenre()
        );
    }

    private void validateKeyword(KeywordDto dto) {
        String category = dto.getCategory();
        if (category == null) {
            return;
        }
        switch (category) {
            case "media.movie" -> requireNonBlank(dto.getLanguage(),
                    "language is required when category is 'media.movie'");
            case "media.celebrity" -> requireNonBlank(dto.getIndustry(),
                    "industry is required when category is 'media.celebrity'");
            case "politics.party" -> requireNonBlank(dto.getState(),
                    "state is required when category is 'politics.party'");
            default -> {
            }
        }
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
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
            basicInfo.setIndustry(entity.getIndustry());
            basicInfo.setGenre(splitGenres(entity.getGenre()));
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
            response.setIndustry(entity.getIndustry());
            response.setGenre(splitGenres(entity.getGenre()));
        }
        return response;
    }
}
