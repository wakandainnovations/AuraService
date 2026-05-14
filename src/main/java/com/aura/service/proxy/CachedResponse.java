package com.aura.service.proxy;

import org.springframework.http.HttpHeaders;

public record CachedResponse(int status, String body, String contentType) {

    public HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null && !contentType.isBlank()) {
            h.add(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return h;
    }
}
