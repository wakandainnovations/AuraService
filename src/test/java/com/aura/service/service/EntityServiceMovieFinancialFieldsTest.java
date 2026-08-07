package com.aura.service.service;

import com.aura.service.dto.CreateEntityRequest;
import com.aura.service.dto.EntityDetailResponse;
import com.aura.service.dto.IndianMacroSnapshot;
import com.aura.service.dto.UpdateEntityRequest;
import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.CheckpointRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the movie-only fields added alongside financial/production details: {@code budget},
 * {@code productionCompany}, and {@code runtime} pass through as given, while {@code releaseDay}
 * and India's {@code gdpUsdBillions}/{@code inflationRatePct} are derived server-side from
 * {@code releaseDate} via {@link IndianMacroEconomicDataService} — never client-supplied.
 */
class EntityServiceMovieFinancialFieldsTest {

    private ManagedEntityRepository entityRepository;
    private EntityAccessService entityAccess;
    private IndianMacroEconomicDataService macroEconomicDataService;
    private EntityService service;

    @BeforeEach
    void setUp() {
        entityRepository = mock(ManagedEntityRepository.class);
        CheckpointRepository checkpointRepository = mock(CheckpointRepository.class);
        MentionRepository mentionRepository = mock(MentionRepository.class);
        entityAccess = mock(EntityAccessService.class);
        LicenseService licenseService = mock(LicenseService.class);
        macroEconomicDataService = mock(IndianMacroEconomicDataService.class);
        service = new EntityService(entityRepository, checkpointRepository, mentionRepository,
                entityAccess, licenseService, macroEconomicDataService, mock(EntityImageMatcher.class));
        when(entityRepository.save(any(ManagedEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(entityAccess.currentUser()).thenReturn(new User());
        when(licenseService.currentMaxEntities()).thenReturn(1000);
        when(licenseService.currentMaxKeywords()).thenReturn(1000);
    }

    @Test
    void createStoresBudgetProductionCompanyAndRuntimeAsGiven() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("KGF Chapter 2");
        request.setBudget(1000000000.0);
        request.setProductionCompany("Hombale Films");
        request.setRuntime(168);

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getBudget()).isEqualTo(1000000000.0);
        assertThat(response.getProductionCompany()).isEqualTo("Hombale Films");
        assertThat(response.getRuntime()).isEqualTo(168);
    }

    @Test
    void createDerivesReleaseDayFromReleaseDateRegardlessOfClientInput() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("KGF Chapter 2");
        // 2022-04-14 was a Thursday; releaseDay must reflect the date, not anything the client sends.
        request.setReleaseDate(LocalDate.of(2022, 4, 14));

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getReleaseDay()).isEqualTo("Thursday");
    }

    @Test
    void createLooksUpIndianMacroDataForTheReleaseYear() {
        LocalDate releaseDate = LocalDate.of(2022, 4, 14);
        when(macroEconomicDataService.lookup(eq(releaseDate)))
                .thenReturn(new IndianMacroSnapshot(3346.11, 6.7));

        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("KGF Chapter 2");
        request.setReleaseDate(releaseDate);

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getGdpUsdBillions()).isEqualTo(3346.11);
        assertThat(response.getInflationRatePct()).isEqualTo(6.7);
        verify(macroEconomicDataService).lookup(releaseDate);
    }

    @Test
    void createLeavesReleaseDayAndMacroFieldsNullWhenNoReleaseDateGiven() {
        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("Untitled Project");

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getReleaseDay()).isNull();
        assertThat(response.getGdpUsdBillions()).isNull();
        assertThat(response.getInflationRatePct()).isNull();
    }

    @Test
    void createLeavesMacroFieldsNullWhenNoDataExistsForTheReleaseYear() {
        LocalDate releaseDate = LocalDate.of(2022, 4, 14);
        when(macroEconomicDataService.lookup(eq(releaseDate))).thenReturn(null);

        CreateEntityRequest request = new CreateEntityRequest();
        request.setName("KGF Chapter 2");
        request.setReleaseDate(releaseDate);

        EntityDetailResponse response = service.createEntity("MOVIE", request);

        assertThat(response.getReleaseDay()).isEqualTo("Thursday");
        assertThat(response.getGdpUsdBillions()).isNull();
        assertThat(response.getInflationRatePct()).isNull();
    }

    @Test
    void updateRederivesReleaseDayAndMacroDataFromTheNewReleaseDate() {
        ManagedEntity existing = new ManagedEntity();
        existing.setId(9L);
        existing.setName("KGF Chapter 2");
        existing.setType("MOVIE");
        existing.setReleaseDate(LocalDate.of(2020, 1, 1));
        existing.setReleaseDay("Wednesday");
        when(entityAccess.assertOwnedByCurrentUser(9L)).thenReturn(existing);

        LocalDate newReleaseDate = LocalDate.of(2022, 4, 14);
        when(macroEconomicDataService.lookup(eq(newReleaseDate)))
                .thenReturn(new IndianMacroSnapshot(3346.11, 6.7));

        UpdateEntityRequest request = new UpdateEntityRequest();
        request.setName("KGF Chapter 2");
        request.setReleaseDate(newReleaseDate);
        request.setBudget(1000000000.0);
        request.setProductionCompany("Hombale Films");
        request.setRuntime(168);

        EntityDetailResponse response = service.updateEntity("MOVIE", 9L, request);

        assertThat(response.getReleaseDay()).isEqualTo("Thursday");
        assertThat(response.getGdpUsdBillions()).isEqualTo(3346.11);
        assertThat(response.getInflationRatePct()).isEqualTo(6.7);
        assertThat(response.getBudget()).isEqualTo(1000000000.0);
        assertThat(response.getProductionCompany()).isEqualTo("Hombale Films");
        assertThat(response.getRuntime()).isEqualTo(168);
    }
}
