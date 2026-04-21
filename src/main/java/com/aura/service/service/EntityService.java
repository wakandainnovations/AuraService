package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityBasicInfo;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.UpdateCompetitorsRequest;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.entity.ManagedEntity;
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
    
    @Transactional
    public EntityDetailResponse createEntity(String entityType, CreateEntityRequest request) {
        ManagedEntity entity = new ManagedEntity();
        entity.setName(request.getName());
        entity.setType(entityType);
        entity.setDirector(request.getDirector());
        entity.setActors(request.getActors());
        entity.setKeywords(request.getKeywords());
        if ("MOVIE".equalsIgnoreCase(entityType)) {
            entity.setReleaseDate(request.getReleaseDate());
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
        
        entity.setKeywords(request.getKeywords());
        
        entity = entityRepository.save(entity);
        
        return mapToDetailResponse(entity);
    }
    
    private EntityBasicInfo mapToBasicInfo(ManagedEntity entity) {
        EntityBasicInfo basicInfo = new EntityBasicInfo(entity.getId(), entity.getName(), entity.getType());
        if ("MOVIE".equalsIgnoreCase(entity.getType())) {
            basicInfo.setDirector(entity.getDirector());
            basicInfo.setReleaseDate(entity.getReleaseDate());
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
        response.setKeywords(entity.getKeywords());
        response.setCompetitors(
                entity.getCompetitors().stream()
                        .map(this::mapToBasicInfo)
                        .collect(Collectors.toList())
        );
        if ("MOVIE".equalsIgnoreCase(entity.getType())) {
            response.setReleaseDate(entity.getReleaseDate());
        }
        return response;
    }
}
