package com.aura.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class AuraServiceApplication {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
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
