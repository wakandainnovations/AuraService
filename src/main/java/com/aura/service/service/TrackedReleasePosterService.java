package com.aura.service.service;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.TrackedRelease;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saves every tracked release's TMDB poster into the entity image folder
 * ({@code entity.images.base-path}), so user-facing entities get a poster without anyone
 * downloading it by hand (as was done for GD Naidu / Toxic).
 *
 * <p>Files are named after the release's entity name with only letters/digits kept (e.g.
 * "O.M: Operation Mole" -> {@code OMOperationMole.jpg}), which is exactly what
 * {@link EntityImageMatcher} normalises to - so creating a managed entity with that name picks the
 * poster up automatically (EntityService), and existing managed entities that still have no image
 * are matched right after each run, same as {@code EntityImageBackfill} does at startup.
 *
 * <p>A file that already exists for a name (any extension, e.g. a hand-saved {@code GDNaidu.jpeg})
 * is never overwritten. Posters come from TMDB's public image CDN (no API key needed); TMDB's terms
 * allow storing them with attribution, which the UI shows in the navigation bar.
 */
@Slf4j
@Service
public class TrackedReleasePosterService {

    static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/";

    /** Downloads one image; separated so tests don't hit the network. */
    interface PosterFetcher {
        byte[] fetch(String url) throws IOException, InterruptedException;
    }

    private final TrackedReleaseRepository trackedReleaseRepository;
    private final ManagedEntityRepository managedEntityRepository;
    private final EntityImageMatcher imageMatcher;
    private final PosterFetcher fetcher;

    @Value("${entity.images.base-path}")
    String imagesBasePath;

    @Value("${tracked-releases.posters.enabled:true}")
    boolean enabled = true;

    // TMDB poster width: w342 / w500 / w780 / original. w500 is plenty for UI cards.
    @Value("${tracked-releases.posters.size:w500}")
    String size = "w500";

    @org.springframework.beans.factory.annotation.Autowired
    public TrackedReleasePosterService(TrackedReleaseRepository trackedReleaseRepository,
                                       ManagedEntityRepository managedEntityRepository,
                                       EntityImageMatcher imageMatcher) {
        this(trackedReleaseRepository, managedEntityRepository, imageMatcher, httpFetcher());
    }

    TrackedReleasePosterService(TrackedReleaseRepository trackedReleaseRepository,
                                ManagedEntityRepository managedEntityRepository,
                                EntityImageMatcher imageMatcher,
                                PosterFetcher fetcher) {
        this.trackedReleaseRepository = trackedReleaseRepository;
        this.managedEntityRepository = managedEntityRepository;
        this.imageMatcher = imageMatcher;
        this.fetcher = fetcher;
    }

    /** Hourly (first run a minute after startup); new releases arrive via discovery every 12h. */
    @Scheduled(initialDelayString = "${tracked-releases.posters.initial-delay:PT1M}",
            fixedDelayString = "${tracked-releases.posters.interval:PT1H}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            downloadMissingPosters();
        } catch (Exception e) {
            log.warn("Tracked-release poster download failed: {}", e.getMessage());
        }
    }

    /** @return number of posters saved this run */
    @Transactional
    public int downloadMissingPosters() {
        Path dir = Path.of(imagesBasePath);
        if (!Files.isDirectory(dir)) {
            log.warn("Skipping tracked-release posters: image folder {} does not exist", dir);
            return 0;
        }
        Map<String, String> existing = imageMatcher.listImageFilesByNormalizedName();
        int saved = 0;
        for (TrackedRelease r : trackedReleaseRepository.findAll()) {
            String poster = r.getPosterPath();
            String normalized = imageMatcher.normalize(r.getEntityName());
            if (poster == null || poster.isBlank() || normalized.isEmpty() || existing.containsKey(normalized)) {
                continue;
            }
            String filename = fileName(r.getEntityName(), poster);
            try {
                byte[] bytes = fetcher.fetch(TMDB_IMAGE_BASE + size + poster);
                if (!looksLikeImage(bytes)) {
                    log.warn("TMDB poster for '{}' was not an image; skipped", r.getEntityName());
                    continue;
                }
                // Write to a temp file and move, so the folder never holds a half-written poster.
                Path tmp = Files.createTempFile(dir, ".poster-", ".part");
                Files.write(tmp, bytes);
                Files.move(tmp, dir.resolve(filename), StandardCopyOption.ATOMIC_MOVE);
                existing.put(normalized, filename);
                saved++;
            } catch (IOException e) {
                log.warn("Could not save TMDB poster for '{}': {}", r.getEntityName(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return saved;
            }
        }
        int linked = linkUnmatchedEntities(existing);
        if (saved > 0 || linked > 0) {
            log.info("Tracked-release posters: saved {} new poster(s), linked {} entity image(s)", saved, linked);
        }
        return saved;
    }

    /** Fills image_path for managed entities still without one, now that new files may exist. */
    private int linkUnmatchedEntities(Map<String, String> filesByNormalizedName) {
        int linked = 0;
        for (ManagedEntity entity : managedEntityRepository.findByImagePathIsNull()) {
            String file = filesByNormalizedName.get(imageMatcher.normalize(entity.getName()));
            if (file != null) {
                entity.setImagePath(file);
                linked++;
            }
        }
        return linked;
    }

    /** "O.M: Operation Mole" + "/abc.jpg" -> "OMOperationMole.jpg" (letters/digits only, matcher-compatible). */
    static String fileName(String entityName, String posterPath) {
        String base = entityName.replaceAll("[^A-Za-z0-9]", "");
        String lower = posterPath.toLowerCase(Locale.ROOT);
        String ext = lower.endsWith(".png") ? ".png" : lower.endsWith(".webp") ? ".webp" : ".jpg";
        return base + ext;
    }

    /** JPEG, PNG or WebP magic bytes - guards against saving an HTML error page as a poster. */
    static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 12) return false;
        boolean jpeg = (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
        boolean png = (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean webp = b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return jpeg || png || webp;
    }

    private static PosterFetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return url -> {
            HttpResponse<byte[]> resp = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
            return resp.body();
        };
    }
}
