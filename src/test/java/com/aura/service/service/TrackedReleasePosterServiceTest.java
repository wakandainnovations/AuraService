package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.TrackedRelease;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TrackedReleasePosterServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};

    @TempDir
    Path dir;

    private TrackedReleaseRepository releases;
    private ManagedEntityRepository entities;
    private EntityImageMatcherImpl matcher;
    private List<String> fetchedUrls;
    private TrackedReleasePosterService service;

    @BeforeEach
    void setUp() {
        releases = mock(TrackedReleaseRepository.class);
        entities = mock(ManagedEntityRepository.class);
        matcher = new EntityImageMatcherImpl();
        ReflectionTestUtils.setField(matcher, "imagesBasePath", dir.toString());
        fetchedUrls = new ArrayList<>();
        service = new TrackedReleasePosterService(releases, entities, matcher, url -> {
            fetchedUrls.add(url);
            return JPEG;
        });
        service.imagesBasePath = dir.toString();
        when(entities.findByImagePathIsNull()).thenReturn(List.of());
    }

    private static TrackedRelease release(String name, String posterPath) {
        TrackedRelease r = new TrackedRelease();
        r.setEntityName(name);
        r.setPosterPath(posterPath);
        return r;
    }

    @Test
    void savesPosterUnderMatcherCompatibleName() throws Exception {
        when(releases.findAll()).thenReturn(List.of(release("O.M: Operation Mole", "/abc.jpg")));

        assertThat(service.downloadMissingPosters()).isEqualTo(1);

        assertThat(fetchedUrls).containsExactly("https://image.tmdb.org/t/p/w500/abc.jpg");
        assertThat(Files.readAllBytes(dir.resolve("OMOperationMole.jpg"))).isEqualTo(JPEG);
        assertThat(matcher.matchFile("O.M: Operation Mole")).isEqualTo("OMOperationMole.jpg");
    }

    @Test
    void neverOverwritesAnExistingImageForTheSameName() throws Exception {
        Files.write(dir.resolve("GDNaidu.jpeg"), new byte[]{1, 2, 3});
        when(releases.findAll()).thenReturn(List.of(release("GD Naidu", "/new.jpg")));

        assertThat(service.downloadMissingPosters()).isZero();
        assertThat(fetchedUrls).isEmpty();
        assertThat(Files.readAllBytes(dir.resolve("GDNaidu.jpeg"))).containsExactly(1, 2, 3);
    }

    @Test
    void skipsRowsWithoutPosterAndNonImageResponses() {
        service = new TrackedReleasePosterService(releases, entities, matcher, url -> "<html>error</html>".getBytes());
        service.imagesBasePath = dir.toString();
        when(releases.findAll()).thenReturn(List.of(release("No Poster", null), release("Broken", "/x.jpg")));

        assertThat(service.downloadMissingPosters()).isZero();
        assertThat(dir.toFile().list()).isEmpty();
    }

    @Test
    void linksExistingEntitiesThatHadNoImage() {
        ManagedEntity entity = new ManagedEntity();
        entity.setName("Itllu Arjuna");
        when(entities.findByImagePathIsNull()).thenReturn(List.of(entity));
        when(releases.findAll()).thenReturn(List.of(release("Itllu Arjuna", "/p.jpg")));

        service.downloadMissingPosters();

        assertThat(entity.getImagePath()).isEqualTo("ItlluArjuna.jpg");
    }

    @Test
    void fileNameKeepsOnlyLettersAndDigits() {
        assertThat(TrackedReleasePosterService.fileName("Bachelor Party D'eux", "/a.JPG")).isEqualTo("BachelorPartyDeux.jpg");
        assertThat(TrackedReleasePosterService.fileName("Lambodhara 2.0", "/a.png")).isEqualTo("Lambodhara20.png");
    }
}
