package com.aura.service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;

@Service
public class LLMServiceImpl implements LLMService {

    @Value("${llm.url}")
    private String llmUrl;

    @Value("${llm.prompt.generate.reply}")
    private String llmGenerateReplyPrompt;

    @Value("${llm.prompt.generate.crisis.plan}")
    private String llmGenerateCrisisPlanPrompt;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LLMServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generateReply(String prompt) {
        return callLlm(prompt);
    }

    @Override
    public String generateCrisisPlan(String prompt) {
        return callLlm(prompt);
    }

    private String callLlm(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, String> requestBody = Collections.singletonMap("prompt", prompt);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);

        try {
            String response = restTemplate.postForObject(llmUrl, entity, String.class);
            String reply;
            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode replyNode = root.path("reply");
                if (replyNode.isMissingNode()) {
                    replyNode = root.path("response");
                }
                if (replyNode.isMissingNode()) {
                    replyNode = root.path("generated_text");
                }
                reply = replyNode.isMissingNode() ? response : replyNode.asText();
            } catch (JsonProcessingException e) {
                // Not a JSON response, return as is.
                reply = response;
            }
            return stripMarkdownJsonFence(reply);
        } catch (Exception e) {
            System.err.println("Error calling LLM service: " + e.getMessage());
            return "Error generating reply from LLM.";
        }
    }

    // Models routinely ignore "output strictly JSON, no other text" instructions and wrap their
    // reply in a ```json ... ``` fence anyway. Callers that objectMapper.readTree() this text need
    // the fence gone first, or parsing fails on the leading backtick. Left untouched (returned as-is)
    // for any reply that isn't fenced, so plain-text replies (e.g. generateReply's normal use) are unaffected.
    private static String stripMarkdownJsonFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) {
            return text;
        }
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline == -1) {
            return text;
        }
        String withoutOpeningFence = trimmed.substring(firstNewline + 1);
        int closingFence = withoutOpeningFence.lastIndexOf("```");
        if (closingFence == -1) {
            return text;
        }
        return withoutOpeningFence.substring(0, closingFence).strip();
    }
}
