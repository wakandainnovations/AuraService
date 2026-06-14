package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.User;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Assigns an owner to any legacy {@code managed_entities} row that predates the ownership column.
 *
 * <p>The schema is managed by {@code ddl-auto=update} (no Flyway), so when the {@code owner_id}
 * column is first added to an already-populated table the existing rows have a null owner. This
 * runner gives them all to the seeded {@code admin} user so they remain reachable (rather than being
 * orphaned and invisible to every user). It is idempotent: once every row has an owner it does
 * nothing, so it is safe to run on every startup.
 *
 * <p>Runs after {@link DataInitializer} (which seeds the users and owned demo data) via {@link Order}.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class EntityOwnerBackfill implements ApplicationRunner {

    private final ManagedEntityRepository entityRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ManagedEntity> orphaned = entityRepository.findByOwnerIsNull();
        if (orphaned.isEmpty()) {
            return;
        }

        User admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            log.warn("Skipping owner backfill for {} entity(ies): no 'admin' user to assign them to",
                    orphaned.size());
            return;
        }

        orphaned.forEach(entity -> entity.setOwner(admin));
        entityRepository.saveAll(orphaned);
        log.info("Backfilled owner=admin for {} managed entity(ies) with no owner", orphaned.size());
    }
}
