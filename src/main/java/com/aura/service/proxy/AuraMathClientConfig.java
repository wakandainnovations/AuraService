package com.aura.service.proxy;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(AuraMathProperties.class)
public class AuraMathClientConfig {

    static final String SYNC_CLIENT_QUALIFIER = "auraMathSyncWebClient";

    @Bean
    public ConnectionProvider auraMathConnectionProvider() {
        return ConnectionProvider.builder("auramath-pool")
                .maxConnections(200)
                .maxIdleTime(Duration.ofSeconds(45))
                .maxLifeTime(Duration.ofMinutes(10))
                .pendingAcquireTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public WebClient auraMathWebClient(AuraMathProperties props, ConnectionProvider provider) {
        return buildClient(props, provider, props.getReadTimeoutMs());
    }

    @Bean(SYNC_CLIENT_QUALIFIER)
    public WebClient auraMathSyncWebClient(AuraMathProperties props, ConnectionProvider provider) {
        return buildClient(props, provider, props.getSyncReadTimeoutMs());
    }

    private WebClient buildClient(AuraMathProperties props, ConnectionProvider provider, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, props.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
