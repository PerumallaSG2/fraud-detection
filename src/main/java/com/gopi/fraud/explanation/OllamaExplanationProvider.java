package com.gopi.fraud.explanation;

import com.gopi.fraud.config.ExplanationProperties;
import com.gopi.fraud.domain.ExplanationSource;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A local model through Ollama's HTTP API ({@code POST /api/generate}, not streamed). If Ollama isn't running the
 * connection is refused at once; a slow answer is cut off at {@code timeout}. Either way the caller falls back.
 */
@Component
@Profile("!bedrock")
public class OllamaExplanationProvider implements ExplanationProvider {

    private final RestClient http;
    private final ExplanationProperties.Ollama config;

    public OllamaExplanationProvider(RestClient.Builder builder, ExplanationProperties properties) {
        this.config = properties.ollama();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .build());
        requestFactory.setReadTimeout(config.timeout());
        this.http = builder.baseUrl(config.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public ExplanationSource source() {
        return ExplanationSource.OLLAMA;
    }

    @Override
    public String model() {
        return config.model();
    }

    @Override
    public String explain(ExplanationPrompt prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("system", prompt.system());
        body.put("prompt", prompt.user());
        body.put("stream", false);
        body.put("keep_alive", "30m");   // keep the model loaded between alerts; loading it is the slow part
        body.put("options", Map.of("num_predict", config.maxTokens(), "temperature", 0.2));
        GenerateResponse response = http.post().uri("/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GenerateResponse.class);
        if (response == null || response.response() == null) {
            throw new IllegalStateException("Ollama returned no response text");
        }
        return response.response();
    }

    /** The part of Ollama's reply that is used. */
    record GenerateResponse(String response) {
    }
}
