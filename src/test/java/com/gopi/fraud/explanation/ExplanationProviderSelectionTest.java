package com.gopi.fraud.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.config.BedrockConfig;
import com.gopi.fraud.config.ExplanationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** Exactly one provider is active: Ollama unless the {@code bedrock} profile is on. No containers, no AWS calls. */
class ExplanationProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class, BedrockConfig.class, OllamaExplanationProvider.class,
                    BedrockExplanationProvider.class);

    @Test
    void ollamaByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ExplanationProvider.class);
            assertThat(context.getBean(ExplanationProvider.class)).isInstanceOf(OllamaExplanationProvider.class);
            assertThat(context).doesNotHaveBean(BedrockRuntimeClient.class);
        });
    }

    @Test
    void bedrockWithTheBedrockProfileUsingTheConfiguredModel() {
        runner.withPropertyValues("spring.profiles.active=bedrock",
                        "fraud.explanations.bedrock.model-id=global.anthropic.claude-haiku-5-5")
                .run(context -> {
                    assertThat(context).hasSingleBean(ExplanationProvider.class);
                    ExplanationProvider provider = context.getBean(ExplanationProvider.class);
                    assertThat(provider).isInstanceOf(BedrockExplanationProvider.class);
                    assertThat(provider.model()).isEqualTo("global.anthropic.claude-haiku-5-5");
                    assertThat(context).hasSingleBean(BedrockRuntimeClient.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ExplanationProperties.class)
    static class Support {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
