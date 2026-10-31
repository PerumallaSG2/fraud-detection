package com.gopi.fraud.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gopi.fraud.StubHttpServer;
import com.gopi.fraud.config.ExplanationProperties;
import com.gopi.fraud.domain.ExplanationSource;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Against a stub of Ollama's HTTP API; no Ollama needed. */
class OllamaExplanationProviderTest {

    private static final ExplanationPrompt PROMPT = new ExplanationPrompt("the instructions", "the data");

    private final StubHttpServer ollama = StubHttpServer.start();
    private final OllamaExplanationProvider provider = provider(ollama.url());

    @AfterEach
    void stop() {
        ollama.close();
    }

    @Test
    void postsANonStreamedGenerateRequestAndReturnsTheAnswer() throws Exception {
        ollama.reply(200, "{\"model\":\"llama3.2:1b\",\"response\":\"Gambling at 02:30.\",\"done\":true}");

        assertThat(provider.explain(PROMPT)).isEqualTo("Gambling at 02:30.");

        StubHttpServer.Request request = ollama.requests().getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/generate");
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertThat(body.get("model").asText()).isEqualTo("llama3.2:1b");
        assertThat(body.get("system").asText()).isEqualTo("the instructions");
        assertThat(body.get("prompt").asText()).isEqualTo("the data");
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("options").get("num_predict").asInt()).isEqualTo(160);
        assertThat(provider.source()).isEqualTo(ExplanationSource.OLLAMA);
        assertThat(provider.model()).isEqualTo("llama3.2:1b");
    }

    @Test
    void anErrorStatusThrows() {
        ollama.reply(500, "{\"error\":\"model not found\"}");

        assertThatThrownBy(() -> provider.explain(PROMPT)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aReplyWithoutAnswerThrows() {
        ollama.reply(200, "{\"done\":true}");

        assertThatThrownBy(() -> provider.explain(PROMPT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSlowAnswerIsCutOffAtTheTimeout() {
        ollama.reply(200, "{\"response\":\"too late\"}").delay(Duration.ofSeconds(3));

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider.explain(PROMPT)).isInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void ollamaNotRunningThrowsAtOnce() {
        String url = ollama.url();
        ollama.close();

        long start = System.nanoTime();
        assertThatThrownBy(() -> provider(url).explain(PROMPT)).isInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    private static OllamaExplanationProvider provider(String url) {
        ExplanationProperties properties = new ExplanationProperties(
                new ExplanationProperties.Ollama(url, "llama3.2:1b", Duration.ofMillis(500), Duration.ofMillis(300),
                        160),
                new ExplanationProperties.Bedrock("global.anthropic.claude-haiku-5-5", "us-east-1",
                        Duration.ofSeconds(10), 1024));
        return new OllamaExplanationProvider(RestClient.builder(), properties);
    }
}
