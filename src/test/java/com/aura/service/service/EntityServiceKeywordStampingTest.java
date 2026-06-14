package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.KeywordDto;
import com.aura.service.dto.UpdateKeywordsRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers how the entity create/update flows derive the {@code entity_keywords}
 * classification columns (category/language/industry/genre) from the entity itself.
 */
class EntityServiceKeywordStampingTest {

    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccess;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        LicenseService licenseService = mock(LicenseService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService);
        // save() returns the entity it was given so the response reflects the stamped keywords.
        when(entityRepository.save(any(ManagedEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // Every create stamps the current user as owner.
        when(entityAccess.currentUser()).thenReturn(new User());
        // Caps are not under test here — keep them comfortably high so create/update never trips them.
        when(licenseService.currentMaxEntities()).thenReturn(1000);
        when(licenseService.currentMaxKeywords()).thenReturn(1000);
    }

    private static KeywordDto keyword(String text) {
        return new KeywordDto(text, null, null, null, null, null);
    }

    @Test
    void movieKeywordsAreStampedOneRowEachWithCommaSeparatedGenres() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Dune: Part Two");
        request.setKeywords(List.of(keyword("dune"), keyword("villeneuve")));
        request.setLanguage("English");
        request.setIndustry("Hollywood");
        request.setGenre(List.of("Science Fiction", "Adventure"));

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        // One row per keyword; the multiple genres live on the single row, comma-separated.
        assertThat(response.getKeywords())
                .extracting(KeywordDto::getKeyword, KeywordDto::getCategory,
                        KeywordDto::getLanguage, KeywordDto::getIndustry, KeywordDto::getGenre)
                .containsExactly(
                        tuple("dune", "media.movie", "English", "Hollywood", "Science Fiction,Adventure"),
                        tuple("villeneuve", "media.movie", "English", "Hollywood", "Science Fiction,Adventure"));
    }

    @Test
    void movieWithNoGenreProducesOneRowPerKeywordWithNullGenre() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("The Matrix");
        request.setKeywords(List.of(keyword("matrix")));
        request.setLanguage("English");
        request.setIndustry("Hollywood");
        request.setGenre(List.of());

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getKeywords()).singleElement()
                .extracting(KeywordDto::getKeyword, KeywordDto::getCategory,
                        KeywordDto::getLanguage, KeywordDto::getIndustry, KeywordDto::getGenre)
                .containsExactly("matrix", "media.movie", "English", "Hollywood", null);
    }

    @Test
    void celebrityKeywordsAreStampedWithCategoryAndIndustryButNoLanguageOrGenre() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Keanu Reeves");
        request.setKeywords(List.of(keyword("keanu")));
        // language and genre are movie-only and must be ignored for celebrities.
        request.setLanguage("English");
        request.setIndustry("Hollywood");
        request.setGenre(List.of("Science Fiction"));

        EntityDetailResponse response = service.createEntity("CELEBRITY", request);

        assertThat(response.getKeywords()).singleElement()
                .extracting(KeywordDto::getKeyword, KeywordDto::getCategory,
                        KeywordDto::getLanguage, KeywordDto::getIndustry, KeywordDto::getGenre)
                .containsExactly("keanu", "media.celebrity", null, "Hollywood", null);
    }

    @Test
    void clientSuppliedKeywordClassificationIsIgnoredInFavorOfTheEntity() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Dune");
        // Bogus per-keyword classification that must not leak into the stored row.
        request.setKeywords(List.of(
                new KeywordDto("dune", "politics.party", "Klingon", "Texas", "Bollywood", "Comedy")));
        request.setLanguage("English");
        request.setIndustry("Hollywood");
        request.setGenre(List.of("Science Fiction"));

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getKeywords()).singleElement()
                .extracting(KeywordDto::getKeyword, KeywordDto::getCategory,
                        KeywordDto::getLanguage, KeywordDto::getState,
                        KeywordDto::getIndustry, KeywordDto::getGenre)
                .containsExactly("dune", "media.movie", "English", null, "Hollywood", "Science Fiction");
    }

    @Test
    void updateKeywordsRestampsFromTheExistingEntityClassification() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(5L);
        existing.setName("Dune");
        existing.setType("MOVIE");
        existing.setLanguage("Tamil");
        existing.setIndustry("Kollywood");
        existing.setGenre("Action");
        when(entityAccess.assertOwnedByCurrentUser(5L)).thenReturn(existing);

        UpdateKeywordsRequest request = new UpdateKeywordsRequest();
        request.setKeywords(List.of(keyword("newterm")));

        EntityDetailResponse response = service.updateKeywords("MOVIE", 5L, request);

        assertThat(response.getKeywords()).singleElement()
                .extracting(KeywordDto::getKeyword, KeywordDto::getCategory,
                        KeywordDto::getLanguage, KeywordDto::getIndustry, KeywordDto::getGenre)
                .containsExactly("newterm", "media.movie", "Tamil", "Kollywood", "Action");
    }
}
