package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.entity.UserEntityView;
import com.aura.service.enums.EntityType;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.enums.LicenseTier;
import com.aura.service.repository.LicenseRepository;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserEntityViewRepository;
import com.aura.service.repository.UserRepository;
import com.aura.service.service.LicensePriceService;
import com.aura.service.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    
    private final UserRepository userRepository;
    private final ManagedEntityRepository entityRepository;
    private final MentionRepository mentionRepository;
    private final UserEntityViewRepository viewRepository;
    private final LicenseRepository licenseRepository;
    private final LicenseService licenseService;
    private final LicensePriceService licensePriceService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        initializeUsers();
        initializeEntities();
        initializeMentions();
        initializeUserEntityViews();
        initializeLicensePrices();
        initializeLicenses();
    }
    
    private void initializeUsers() {
        if (userRepository.count() == 0) {
            User user = new User();
            user.setUsername("user");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole("ROLE_USER");
            user.setTimezone("America/New_York");
            userRepository.save(user);

            System.out.println("Default user created: username=user, password=password");

            // Admin account — required to read the audit trail at /api/audit-logs (ROLE_ADMIN only).
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin"));
            admin.setRole("ROLE_ADMIN");
            admin.setTimezone("America/New_York");
            userRepository.save(admin);

            System.out.println("Default admin created: username=admin, password=admin");
        }
    }
    
    private void initializeEntities() {
        if (entityRepository.count() == 0) {
            // The demo data belongs to the default "user" account (the same account the seeded
            // user_entity_views are created for), so the demo dashboards are populated out of the box.
            User owner = userRepository.findByUsername("user").orElse(null);

            ManagedEntity karuppu = new ManagedEntity();
            karuppu.setName("Karuppu");
            karuppu.setType(EntityType.MOVIE.name());
            karuppu.setDirector("RJ Balaji");
            karuppu.setActors(Arrays.asList("Surya", "RJ Balaji", "Trisha"));
            karuppu.setOwner(owner);
            entityRepository.save(karuppu);

            ManagedEntity surya = new ManagedEntity();
            surya.setName("Surya");
            surya.setType(EntityType.CELEBRITY.name());
            surya.setOwner(owner);
            entityRepository.save(surya);

            karuppu.setCompetitors(List.of(surya));
            entityRepository.save(karuppu);

            surya.setCompetitors(List.of(karuppu));
            entityRepository.save(surya);

            System.out.println("Sample entities created: Karuppu (id=1), Surya (id=2)");
        }
    }
    
    private void initializeMentions() {
        if (mentionRepository.count() == 0) {
            List<ManagedEntity> entities = entityRepository.findAll();
            if (entities.isEmpty()) {
                return;
            }

            Instant lastSeen = Instant.now().minus(30, ChronoUnit.DAYS);
            Random random = new Random(42);
            Platform[] platforms = Platform.values();

            String[] authors = {"tamil_cinephile", "movie_fan_123", "critic_sarah",
                    "film_buff", "kollywood_news", "cinema_lover"};

            String[] positiveComments = {
                "This movie is absolutely amazing! Best film of the year!",
                "Incredible performance! Oscar-worthy for sure.",
                "Just watched it and I'm blown away. Masterpiece!",
                "The cinematography is stunning. Highly recommend!",
                "Can't stop thinking about this movie. Brilliant storytelling!"
            };

            String[] negativeComments = {
                "Very disappointed. Expected much better.",
                "Waste of time and money. Don't bother watching.",
                "Overrated and boring. Not worth the hype.",
                "Poor script and weak performances.",
                "Couldn't even finish watching. That bad."
            };

            String[] neutralComments = {
                "It was okay. Nothing special but not terrible either.",
                "Average movie. Some good parts, some not so good.",
                "Mixed feelings about this one.",
                "Decent entertainment but forgettable.",
                "It has its moments but overall just okay."
            };

            ManagedEntity karuppu = entities.stream()
                    .filter(e -> e.getName().equals("Karuppu")).findFirst().orElseThrow();
            ManagedEntity surya = entities.stream()
                    .filter(e -> e.getName().equals("Surya")).findFirst().orElseThrow();

            // --- Karuppu: before lastSeen — balanced baseline (10 pos, 10 neg) ---
            seedMentions(karuppu, Sentiment.POSITIVE, 10, 60, 31, positiveComments, authors, platforms, random);
            seedMentions(karuppu, Sentiment.NEGATIVE, 10, 60, 31, negativeComments, authors, platforms, random);
            seedMentions(karuppu, Sentiment.NEUTRAL, 5, 60, 31, neutralComments, authors, platforms, random);

            // --- Karuppu: after lastSeen — strong positive surge + some negatives ---
            // 20 positive vs 6 negative → sentiment rise + negative spike (≥5)
            seedMentions(karuppu, Sentiment.POSITIVE, 20, 29, 1, positiveComments, authors, platforms, random);
            seedMentions(karuppu, Sentiment.NEGATIVE, 6, 29, 1, negativeComments, authors, platforms, random);
            seedMentions(karuppu, Sentiment.NEUTRAL, 3, 29, 1, neutralComments, authors, platforms, random);

            // --- Surya (competitor): before lastSeen — balanced baseline (10 pos, 10 neg) ---
            seedMentions(surya, Sentiment.POSITIVE, 10, 60, 31, positiveComments, authors, platforms, random);
            seedMentions(surya, Sentiment.NEGATIVE, 10, 60, 31, negativeComments, authors, platforms, random);
            seedMentions(surya, Sentiment.NEUTRAL, 5, 60, 31, neutralComments, authors, platforms, random);

            // --- Surya: after lastSeen — sentiment drops (3 pos, 15 neg) → triggers COMPETITOR_DROP for Karuppu ---
            seedMentions(surya, Sentiment.POSITIVE, 3, 29, 1, positiveComments, authors, platforms, random);
            seedMentions(surya, Sentiment.NEGATIVE, 15, 29, 1, negativeComments, authors, platforms, random);

            System.out.println("Demo mentions created for Karuppu and Surya");
        }
    }

    private void seedMentions(ManagedEntity entity, Sentiment sentiment, int count,
                              int maxDaysAgo, int minDaysAgo, String[] comments,
                              String[] authors, Platform[] platforms, Random random) {
        for (int i = 0; i < count; i++) {
            Mention mention = new Mention();
            mention.setManagedEntity(entity);
            mention.setPlatform(platforms[random.nextInt(platforms.length)]);
            mention.setPostId(entity.getName() + "_" + sentiment + "_" + i);
            mention.setSentiment(sentiment);
            mention.setContent(comments[random.nextInt(comments.length)]);
            mention.setAuthor(authors[random.nextInt(authors.length)]);
            int daysAgo = minDaysAgo + random.nextInt(maxDaysAgo - minDaysAgo + 1);
            mention.setPostDate(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
            mentionRepository.save(mention);
        }
    }

    private void initializeLicensePrices() {
        // Seed the four tier price rows (price 0) if missing. Prices are admin-only data and are
        // never exposed to regular users.
        licensePriceService.seedDefaults();
    }

    private void initializeLicenses() {
        // Give the seeded accounts a license so the licensing flow is usable out of the box.
        if (licenseRepository.count() == 0) {
            userRepository.findByUsername("user")
                    .ifPresent(u -> licenseService.issueLicense(u.getId(), LicenseTier.GOLD, null));
            userRepository.findByUsername("admin")
                    .ifPresent(u -> licenseService.issueLicense(u.getId(), LicenseTier.DIAMOND, null));
            System.out.println("Licenses issued: user=GOLD, admin=DIAMOND");
        }
    }

    private void initializeUserEntityViews() {
        if (viewRepository.count() == 0) {
            User user = userRepository.findByUsername("user").orElse(null);
            if (user == null) {
                return;
            }
            List<ManagedEntity> entities = entityRepository.findAll();
            Instant lastSeen = Instant.now().minus(30, ChronoUnit.DAYS);
            for (ManagedEntity entity : entities) {
                UserEntityView view = new UserEntityView();
                view.setUserId(user.getId());
                view.setEntityId(entity.getId());
                view.setLastSeenAt(lastSeen);
                viewRepository.save(view);
            }
            System.out.println("User entity views created with lastSeenAt = 30 days ago");
        }
    }
}
