package com.aura.service.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auramath")
public class AuraMathProperties {

    private String baseUrl = "http://localhost:8081";
    private int connectTimeoutMs = 30_000;
    private int readTimeoutMs = 60_000;
    private int syncReadTimeoutMs = 600_000;
    private int marketingTimeoutMs = 15_000;
    private Cache cache = new Cache();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public int getSyncReadTimeoutMs() { return syncReadTimeoutMs; }
    public void setSyncReadTimeoutMs(int syncReadTimeoutMs) { this.syncReadTimeoutMs = syncReadTimeoutMs; }

    public int getMarketingTimeoutMs() { return marketingTimeoutMs; }
    public void setMarketingTimeoutMs(int marketingTimeoutMs) { this.marketingTimeoutMs = marketingTimeoutMs; }

    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }

    public static class Cache {
        private int defaultTtlSeconds = 60;
        private int categoriesTtlSeconds = 300;
        private int listTtlSeconds = 300;
        private int maxEntries = 1000;

        public int getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(int defaultTtlSeconds) { this.defaultTtlSeconds = defaultTtlSeconds; }

        public int getCategoriesTtlSeconds() { return categoriesTtlSeconds; }
        public void setCategoriesTtlSeconds(int categoriesTtlSeconds) { this.categoriesTtlSeconds = categoriesTtlSeconds; }

        public int getListTtlSeconds() { return listTtlSeconds; }
        public void setListTtlSeconds(int listTtlSeconds) { this.listTtlSeconds = listTtlSeconds; }

        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }
}
