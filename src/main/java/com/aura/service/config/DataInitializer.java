package com.aura.service.config;

import com.aura.service.entity.ManagedEntity;
import com.aura.service.entity.Mention;
import com.aura.service.entity.User;
import com.aura.service.enums.EntityType;
import com.aura.service.enums.Platform;
import com.aura.service.enums.Sentiment;
import com.aura.service.repository.ManagedEntityRepository;
import com.aura.service.repository.MentionRepository;
import com.aura.service.repository.UserRepository;
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
    private final PasswordEncoder passwordEncoder;
    
    @Override
    public void run(String... args) {
        initializeUsers();
        initializeEntities();
        initializeMentions();
    }
    
    private void initializeUsers() {
        if (userRepository.count() == 0) {
            User user = new User();
            user.setUsername("user");
            user.setPassword(passwordEncoder.encode("password"));
            user.setRole("ROLE_USER");
            userRepository.save(user);
            
            System.out.println("Default user created: username=user, password=password");
        }
    }
    
    private void initializeEntities() {
        if (entityRepository.count() == 0) {
            ManagedEntity movie = new ManagedEntity();
            movie.setName("The Quantum Paradox");
            movie.setType(EntityType.MOVIE.name());
            movie.setDirector("Christopher Nolan");
            movie.setActors(Arrays.asList("Leonardo DiCaprio", "Emma Stone", "Tom Hardy"));
            entityRepository.save(movie);
            
            ManagedEntity celebrity = new ManagedEntity();
            celebrity.setName("Emma Stone");
            celebrity.setType(EntityType.CELEBRITY.name());
            entityRepository.save(celebrity);
            
            ManagedEntity competitor1 = new ManagedEntity();
            competitor1.setName("Inception 2");
            competitor1.setType(EntityType.MOVIE.name());
            competitor1.setDirector("Denis Villeneuve");
            competitor1.setActors(Arrays.asList("Ryan Gosling", "Margot Robbie"));
            entityRepository.save(competitor1);
            
            ManagedEntity competitor2 = new ManagedEntity();
            competitor2.setName("Interstellar Reloaded");
            competitor2.setType(EntityType.MOVIE.name());
            competitor2.setDirector("James Cameron");
            competitor2.setActors(Arrays.asList("Zendaya", "Timothée Chalamet"));
            entityRepository.save(competitor2);
            
            movie.setCompetitors(Arrays.asList(competitor1, competitor2));
            entityRepository.save(movie);
            
            System.out.println("Sample entities created");
        }
    }
    
    private void initializeMentions() {
        if (mentionRepository.count() == 0) {
            List<ManagedEntity> entities = entityRepository.findAll();
            if (entities.isEmpty()) {
                return;
            }
            
            Random random = new Random();
            Platform[] platforms = Platform.values();
            Sentiment[] sentiments = Sentiment.values();
            String[] countries = {"USA", "UK", "Canada", "Australia", "India", "Germany", "France"};
            String[] cities = {"New York", "London", "Toronto", "Sydney", "Mumbai", "Berlin", "Paris"};
            String[] authors = {"john_doe", "movie_fan_123", "critic_sarah", "film_buff", "entertainment_news", "pop_culture_fan"};
            
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

            String[] totalComments = {
                    "It was okay. Nothing special but not terrible either.",
                    "Average movie. Some good parts, some not so good.",
                    "Mixed feelings about this one.",
                    "Decent entertainment but forgettable.",
                    "It has its moments but overall just okay."
            };

            for (ManagedEntity entity : entities) {
                for (int i = 0; i < 50; i++) {
                    Mention mention = new Mention();
                    mention.setManagedEntity(entity);
                    mention.setPlatform(platforms[random.nextInt(platforms.length)]);
                    mention.setPostId(entity.getName().replaceAll(" ", "_") + "_post_" + i);
                    
                    Sentiment sentiment = sentiments[random.nextInt(sentiments.length)];
                    mention.setSentiment(sentiment);
                    
                    String content = switch (sentiment) {
                        case POSITIVE -> positiveComments[random.nextInt(positiveComments.length)];
                        case NEGATIVE -> negativeComments[random.nextInt(negativeComments.length)];
                        case NEUTRAL -> neutralComments[random.nextInt(neutralComments.length)];
                        case TOTAL -> totalComments[random.nextInt(totalComments.length)];
                    };
                    mention.setContent(content);
                    
                    mention.setAuthor(authors[random.nextInt(authors.length)]);

                    int daysAgo = random.nextInt(90);
                    mention.setPostDate(Instant.now().minus(daysAgo, ChronoUnit.DAYS));
                    
                    mentionRepository.save(mention);
                }
            }
            
            System.out.println("Sample mentions created");
        }
    }
}
