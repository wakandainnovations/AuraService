package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the keyword-mutating entity flows re-derive the entity's {@code mention_entities}
 * links, so an entity created (or re-keyworded) for keywords whose mentions are already in the table
 * picks up that history instead of showing empty dashboards.
 */
class EntityServiceMentionBackfillTest {

    private ManagedEntityRepository entityRepository;
    private MentionRepository mentionRepository;
    private EntityAccessService entityAccess;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        LicenseService licenseService = mock(LicenseService.class);
        IndianMacroEconomicDataService macroEconomicDataService = mock(IndianMacroEconomicDataService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService, macroEconomicDataService, mock(EntityImageMatcher.class));
        // save() assigns an id (as IDENTITY would) so the backfill is invoked with the persisted id.
        when(entityRepository.save(any(ManagedEntity.class))).thenAnswer(invocation -> {
            ManagedEntity e = invocation.getArgument(0);
            if (e.getId() == null) {
                e.setId(65L);
            }
            return e;
        });
        when(entityAccess.currentUser()).thenReturn(new User());
        when(licenseService.currentMaxEntities()).thenReturn(1000);
        when(licenseService.currentMaxKeywords()).thenReturn(1000);
    }

    private static KeywordDto keyword(String text) {
        return new KeywordDto(text, null, null, null, null, null);
    }

    @Test
    void createLinksExistingMentionsForTheNewEntitysKeywords() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Dune: Part Two");
        request.setIndustry("Hollywood");
        request.setLanguage("English");
        request.setKeywords(List.of(keyword("dune")));

        service.createEntity("MOVIE", request);

        // Links are re-derived after the entity (and its keyword rows) are saved: stale rows are
        // cleared first, then matching mentions are attributed to the new entity.
        InOrder order = inOrder(entityRepository, mentionRepository);
        order.verify(entityRepository).save(any(ManagedEntity.class));
        order.verify(mentionRepository).unlinkStaleMentionsByKeyword(65L);
        order.verify(mentionRepository).linkExistingMentionsByKeyword(65L);
    }

    @Test
    void updateKeywordsReDerivesMentionLinksFromTheNewKeywordSet() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(65L);
        existing.setName("Dune");
        existing.setType("MOVIE");
        existing.setLanguage("English");
        existing.setIndustry("Hollywood");
        when(entityAccess.assertOwnedByCurrentUser(65L)).thenReturn(existing);

        UpdateKeywordsRequest request = new UpdateKeywordsRequest();
        request.setKeywords(List.of(keyword("paul atreides")));

        service.updateKeywords("MOVIE", 65L, request);

        verify(mentionRepository).unlinkStaleMentionsByKeyword(65L);
        verify(mentionRepository).linkExistingMentionsByKeyword(65L);
    }
}
