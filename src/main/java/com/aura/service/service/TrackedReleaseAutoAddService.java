package com.aura.service.service;

import com.aura.service.entity.TrackedRelease;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.TrackedReleaseRepository;
import com.aura.service.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Puts every tracked release on the platform automatically, owned by one configured user
 * ({@code tracked-releases.auto-add.owner}, "Bahubali" by default) — the scheduled equivalent of
 * clicking "Add to platform" on each row of the Tracked Releases admin page. Without it a release
 * the collector is scanning stays invisible to the dashboards and the public Whistle site until an
 * admin adds it by hand.
 *
 * <p>Runs a minute after startup and then every {@code tracked-releases.auto-add.interval}. Skips:
 * <ul>
 *   <li>excluded releases (an admin decided they're not worth tracking; restoring one gets it added
 *       on the next run);</li>
 *   <li>releases already on the platform under that name for <em>any</em> owner, so this never
 *       creates a second copy of a movie someone already tracks (the Whistle site and admin views
 *       read every owner's movies).</li>
 * </ul>
 * The owner's license caps on entities and keywords are deliberately not applied
 * ({@link EntityService#createEntityForOwnerIgnoringLimits}): every tracked movie must reach the
 * platform. A release that fails for any other reason is logged and retried on the next run.
 */
@Slf4j
@Service
public class TrackedReleaseAutoAddService {

    private static final String MOVIE = "MOVIE";

    private final TrackedReleaseRepository trackedReleaseRepository;
    private final ManagedEntityRepository managedEntityRepository;
    private final UserRepository userRepository;
    private final EntityService entityService;

    @Value("${tracked-releases.auto-add.enabled:true}")
    private boolean enabled = true;

    @Value("${tracked-releases.auto-add.owner:Bahubali}")
    private String ownerUsername = "Bahubali";

    public TrackedReleaseAutoAddService(TrackedReleaseRepository trackedReleaseRepository,
                                        ManagedEntityRepository managedEntityRepository,
                                        UserRepository userRepository,
                                        EntityService entityService) {
        this.trackedReleaseRepository = trackedReleaseRepository;
        this.managedEntityRepository = managedEntityRepository;
        this.userRepository = userRepository;
        this.entityService = entityService;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    /** Result of one run, for the log line and tests. */
    public record Result(int added, int alreadyOnPlatform, int excluded, int failed) {}

    @Scheduled(initialDelayString = "${tracked-releases.auto-add.initial-delay:PT1M}",
            fixedDelayString = "${tracked-releases.auto-add.interval:PT1H}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            addAllToPlatform();
        } catch (Exception e) {
            log.error("Auto-adding tracked releases to the platform failed", e);
        }
    }

    public Result addAllToPlatform() {
        Optional<User> owner = userRepository.findByUsername(ownerUsername);
        if (owner.isEmpty()) {
            log.warn("Tracked releases not auto-added: user '{}' (tracked-releases.auto-add.owner) doesn't exist", ownerUsername);
            return new Result(0, 0, 0, 0);
        }
        int added = 0, onPlatform = 0, excluded = 0, failed = 0;
        for (TrackedRelease r : trackedReleaseRepository.findAllByOrderByReleaseDateAscEntityNameAsc()) {
            if (r.isExcluded()) {
                excluded++;
                continue;
            }
            if (!managedEntityRepository.findByTypeAndNameIgnoreCase(MOVIE, r.getEntityName()).isEmpty()) {
                onPlatform++;
                continue;
            }
            try {
                // Each call is its own transaction (EntityService is a separate bean), so one failure
                // doesn't roll back the movies added before it.
                entityService.createEntityForOwnerIgnoringLimits(owner.get(), MOVIE, TrackedReleaseServiceImpl.toCreateEntityRequest(r));
                added++;
                log.info("Added tracked release '{}' to the platform for {}", r.getEntityName(), ownerUsername);
            } catch (Exception e) {
                failed++;
                log.warn("Tracked release '{}' not added for {}: {}", r.getEntityName(), ownerUsername, e.getMessage());
            }
        }
        if (added > 0 || failed > 0) {
            log.info("Tracked releases auto-add for {}: {} added, {} already on the platform, {} excluded, {} failed",
                    ownerUsername, added, onPlatform, excluded, failed);
        }
        return new Result(added, onPlatform, excluded, failed);
    }
}
