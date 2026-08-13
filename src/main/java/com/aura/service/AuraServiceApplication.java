package com.aura.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AuraServiceApplication {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // The app had no explicit TaskScheduler bean until RecommendedActionsService needed one (to
    // dynamically self-schedule its refresh cycle at computed Instants - something plain @Scheduled
    // fixedDelay can't express). Since it's the only such bean, Spring wires it in as the scheduler
    // for every @Scheduled method app-wide, not just that service - sized well above this app's
    // handful of periodic jobs plus one entity-refresh task at a time so none of them queue behind
    // each other waiting for a free thread.
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("app-scheduler-");
        scheduler.initialize();
        return scheduler;
    }


	public static void main(String[] args) {
		SpringApplication.run(AuraServiceApplication.class, args);
		
		System.out.println("AuraService is running!");
		System.out.println("Available endpoints:");
		System.out.println("  GET  /api/dashboard/{entityType}/{entityId}/stats - Get entity statistics");
		System.out.println("  GET  /api/dashboard/{entityType}/{entityId}/competitor-snapshot - Get competitor snapshot");
		System.out.println("  GET  /api/dashboard/{entityType}/{entityId}/sentiment-over-time - Get sentiment over time");
		System.out.println("  GET  /api/dashboard/{entityType}/{entityId}/platform-mentions - Get platform breakdown with sentiment");
		System.out.println("  GET  /api/dashboard/{entityType}/{entityId}/mentions - Get mentions with optional filters");
	}

}
