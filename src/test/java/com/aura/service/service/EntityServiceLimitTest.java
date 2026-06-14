package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.LicenseUsageResponse;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.enums.LicenseTier;
import com.aura.service.exception.LimitException;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers enforcement of the per-tier entity and keyword caps (see {@link LicenseTier}) on the entity
 * create and update-keywords paths, plus the usage read-out. All collaborators are mocked as
 * interfaces ({@link ManagedEntityRepository}, {@link EntityAccessService}, {@link LicenseService})
 * — never concrete classes.
 */
class EntityServiceLimitTest {

    private static final Long USER_ID = 1L;

    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccess;
    private LicenseService licenseService;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        licenseService = mock(LicenseService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService);
        when(entityRepository.save(any(ManagedEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entityAccess.currentUser()).thenReturn(user(USER_ID));
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setUsername("alice");
        u.setPassword("x");
        u.setRole("ROLE_USER");
        return u;
    }

    private static KeywordDto keyword(String text) {
        return new KeywordDto(text, null, null, null, null, null);
    }

    private static CreateEntityRequest movieRequest(String name) {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName(name);
        request.setIndustry("Hollywood");
        request.setLanguage("English");
        return request;
    }

    // ---- Entity cap ----

    @Test
    void createAtEntityCapIsRejectedWith409EntitiesBody_andNothingIsCreated() {
        // Bronze allows 5 entities and the user already owns 5 — at the cap, so create must be refused.
        when(licenseService.currentMaxEntities()).thenReturn(LicenseTier.BRONZE.getMaxEntities());
        when(entityRepository.countByOwnerId(USER_ID)).thenReturn(5L);

        LimitException ex = catchThrowableOfType(
                () -> service.createEntity("MOVIE", movieRequest("Dune")),
                LimitException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getLimitType()).isEqualTo(LimitException.LimitType.ENTITIES);
        assertThat(ex.getLimit()).isEqualTo(5);
        assertThat(ex.getCurrent()).isEqualTo(5);
        // "Do not create" — no entity was persisted.
        verify(entityRepository, never()).save(any(ManagedEntity.class));
    }

    @Test
    void createBelowEntityCapSucceeds() {
        when(licenseService.currentMaxEntities()).thenReturn(LicenseTier.BRONZE.getMaxEntities());
        when(licenseService.currentMaxKeywords()).thenReturn(LicenseTier.BRONZE.getMaxKeywords());
        when(entityRepository.countByOwnerId(USER_ID)).thenReturn(4L);

        EntityDetailResponse response = service.createEntity("MOVIE", movieRequest("Dune"));

        assertThat(response.getName()).isEqualTo("Dune");
        verify(entityRepository).save(any(ManagedEntity.class));
    }

    // ---- Keyword cap (counted across ALL of the user's entities) ----

    @Test
    void updateKeywordsRejectedWhenTotalAcrossAllEntitiesWouldExceedCap() {
        // Silver caps keywords at 10. Other entities already hold 8; replacing this entity's keywords
        // with 3 would make 11 across the account — over the cap.
        when(licenseService.currentMaxKeywords()).thenReturn(LicenseTier.SILVER.getMaxKeywords());
        ManagedEntity edited = existingMovie(5L);
        when(entityAccess.assertOwnedByCurrentUser(5L)).thenReturn(edited);
        when(entityRepository.countKeywordsByOwnerIdExcludingEntity(USER_ID, 5L)).thenReturn(8L);

        UpdateKeywordsRequest request = new UpdateKeywordsRequest();
        request.setKeywords(List.of(keyword("a"), keyword("b"), keyword("c")));

        LimitException ex = catchThrowableOfType(
                () -> service.updateKeywords("MOVIE", 5L, request),
                LimitException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getLimitType()).isEqualTo(LimitException.LimitType.KEYWORDS);
        assertThat(ex.getLimit()).isEqualTo(10);
        assertThat(ex.getCurrent()).isEqualTo(11);
        verify(entityRepository, never()).save(any(ManagedEntity.class));
    }

    @Test
    void updateKeywordsAllowedWhenTotalAcrossAllEntitiesStaysWithinCap() {
        // 8 elsewhere + 2 here = exactly 10, which is allowed (cap is inclusive).
        when(licenseService.currentMaxKeywords()).thenReturn(LicenseTier.SILVER.getMaxKeywords());
        ManagedEntity edited = existingMovie(5L);
        when(entityAccess.assertOwnedByCurrentUser(5L)).thenReturn(edited);
        when(entityRepository.countKeywordsByOwnerIdExcludingEntity(USER_ID, 5L)).thenReturn(8L);

        UpdateKeywordsRequest request = new UpdateKeywordsRequest();
        request.setKeywords(List.of(keyword("a"), keyword("b")));

        EntityDetailResponse response = service.updateKeywords("MOVIE", 5L, request);

        assertThat(response.getKeywords()).extracting(KeywordDto::getKeyword)
                .containsExactly("a", "b");
        verify(entityRepository).save(any(ManagedEntity.class));
    }

    // ---- Higher tier (Diamond) grants higher caps ----

    @Test
    void diamondUserGetsHigherCaps_createAllowedAtCountThatLowerTiersWouldReject() {
        // 15 owned entities would breach Bronze (5), but Diamond allows 20 — so the create proceeds.
        when(licenseService.currentMaxEntities()).thenReturn(LicenseTier.DIAMOND.getMaxEntities());
        when(licenseService.currentMaxKeywords()).thenReturn(LicenseTier.DIAMOND.getMaxKeywords());
        when(entityRepository.countByOwnerId(USER_ID)).thenReturn(15L);

        EntityDetailResponse response = service.createEntity("MOVIE", movieRequest("Avatar"));

        assertThat(response.getName()).isEqualTo("Avatar");
        verify(entityRepository).save(any(ManagedEntity.class));
        // Sanity: the same count is over Bronze's entity cap, confirming the higher tier is what allows it.
        assertThat(15).isGreaterThan(LicenseTier.BRONZE.getMaxEntities());
    }

    // ---- Usage read-out ----

    @Test
    void currentUsageReportsCountsAgainstTierMaxima() {
        when(licenseService.currentTier()).thenReturn(LicenseTier.GOLD);
        when(entityRepository.countByOwnerId(USER_ID)).thenReturn(3L);
        when(entityRepository.countKeywordsByOwnerId(USER_ID)).thenReturn(12L);

        LicenseUsageResponse usage = service.currentUsage();

        assertThat(usage.entitiesUsed()).isEqualTo(3L);
        assertThat(usage.entitiesMax()).isEqualTo(LicenseTier.GOLD.getMaxEntities());
        assertThat(usage.keywordsUsed()).isEqualTo(12L);
        assertThat(usage.keywordsMax()).isEqualTo(LicenseTier.GOLD.getMaxKeywords());
    }

    private ManagedEntity existingMovie(Long id) {
        ManagedEntity entity = new ManagedEntity();
        entity.setId(id);
        entity.setName("Existing");
        entity.setType("MOVIE");
        entity.setLanguage("English");
        entity.setIndustry("Hollywood");
        entity.setOwner(user(USER_ID));
        return entity;
    }
}
