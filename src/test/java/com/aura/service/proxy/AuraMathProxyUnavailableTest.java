package com.aura.service.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuraMathProxyUnavailableTest {

    @Test
    void upstreamConnectionRefused_returns504_withUnavailableEnvelope() throws Exception {
        MockWebServer ephemeral = new MockWebServer();
        ephemeral.start();
        int closedPort = ephemeral.getPort();
        String baseUrl = ephemeral.url("/").toString().replaceAll("/$", "");
        ephemeral.shutdown();

        AuraMathProperties props = new AuraMathProperties();
        props.setBaseUrl(baseUrl);
        props.setConnectTimeoutMs(1_000);
        props.setReadTimeoutMs(2_000);
        props.setSyncReadTimeoutMs(2_000);

        AuraMathClientConfig config = new AuraMathClientConfig();
        var provider = config.auraMathConnectionProvider();
        AuraMathProxyService proxyService = new AuraMathProxyService(
                config.auraMathWebClient(props, provider),
                config.auraMathSyncWebClient(props, provider),
                props,
                new ObjectMapper()
        );
        AuraMathProxyController controller = new AuraMathProxyController(proxyService, props);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/v1/viral-seeds").param("keyword", "anything"))
                .andExpect(status().is(504))
                .andExpect(jsonPath("$.error").value("upstream_unavailable"))
                .andExpect(jsonPath("$.endpoint").value("/v1/viral-seeds"));
        // closedPort is captured so failure messages can include it if needed.
        if (closedPort <= 0) throw new IOException("invalid port");
    }
}
