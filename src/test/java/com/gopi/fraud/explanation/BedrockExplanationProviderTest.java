package com.gopi.fraud.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gopi.fraud.StubHttpServer;
import com.gopi.fraud.config.ExplanationProperties;
import com.gopi.fraud.domain.ExplanationSource;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** The real AWS SDK client against a stub of the Converse endpoint, with fake credentials; no AWS needed. */
class BedrockExplanationProviderTest {

    private static final String MODEL = "global.anthropic.claude-haiku-5-5";
    private static final ExplanationPrompt PROMPT = new ExplanationPrompt("the instructions", "the data");

    private final StubHttpServer bedrock = StubHttpServer.start();
    private final BedrockRuntimeClient client = BedrockRuntimeClient.builder()
            .endpointOverride(URI.create(bedrock.url()))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .overrideConfiguration(override -> override.retryStrategy(retry -> retry.maxAttempts(1)))
            .build();
    private final BedrockExplanationProvider provider = new BedrockExplanationProvider(client,
            new ExplanationProperties(
                    new ExplanationProperties.Ollama("http://localhost:1", "llama3.2:1b", Duration.ofMillis(500),
                            Duration.ofSeconds(5), 160),
                    new ExplanationProperties.Bedrock(MODEL, "us-east-1", Duration.ofSeconds(10), 1024)));

    @AfterEach
    void stop() {
        client.close();
        bedrock.close();
    }

    @Test
    void conversesWithTheConfiguredModelAndReturnsOnlyTheText() throws Exception {
        bedrock.reply(200, converseReply("end_turn", """
                {"reasoningContent":{"reasoningText":{"text":"internal reasoning","signature":"sig"}}},
                {"text":"Gambling merchant at 02:30 local time."}"""));

        assertThat(provider.explain(PROMPT)).isEqualTo("Gambling merchant at 02:30 local time.");

        StubHttpServer.Request request = bedrock.requests().getFirst();
        assertThat(request.path()).isEqualTo("/model/" + MODEL + "/converse");
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertThat(body.get("system").get(0).get("text").asText()).isEqualTo("the instructions");
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").get(0).get("text").asText()).isEqualTo("the data");
        assertThat(body.get("inferenceConfig").get("maxTokens").asInt()).isEqualTo(1024);
        // Nothing model-specific, so any Converse model works (Claude Haiku 5.5 rejects a temperature).
        assertThat(body.get("inferenceConfig").has("temperature")).isFalse();
        assertThat(body.has("additionalModelRequestFields")).isFalse();
        assertThat(provider.source()).isEqualTo(ExplanationSource.BEDROCK);
        assertThat(provider.model()).isEqualTo(MODEL);
    }

    @Test
    void anAnswerThatDidNotEndItsTurnThrows() {
        for (String stopReason : new String[] {"max_tokens", "content_filtered", "guardrail_intervened"}) {
            bedrock.reply(200, converseReply(stopReason, "{\"text\":\"partial\"}"));

            assertThatThrownBy(() -> provider.explain(PROMPT)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(stopReason);
        }
    }

    @Test
    void anErrorResponseThrows() {
        bedrock.reply(400, "{\"message\":\"The provided model identifier is invalid.\"}");

        assertThatThrownBy(() -> provider.explain(PROMPT)).isInstanceOf(RuntimeException.class);
    }

    private static String converseReply(String stopReason, String content) {
        return """
                {"output":{"message":{"role":"assistant","content":[%s]}},"stopReason":"%s",
                 "usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15},"metrics":{"latencyMs":5}}"""
                .formatted(content, stopReason);
    }
}
