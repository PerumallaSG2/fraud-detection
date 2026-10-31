package com.gopi.fraud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * The Bedrock client, only with the {@code bedrock} profile. Credentials come from the default AWS chain
 * (environment, profile, SSO, instance role). One retry at most, within {@code timeout} for the whole call.
 */
@Configuration(proxyBeanMethods = false)
@Profile("bedrock")
public class BedrockConfig {

    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(ExplanationProperties properties) {
        ExplanationProperties.Bedrock bedrock = properties.bedrock();
        return BedrockRuntimeClient.builder()
                .region(Region.of(bedrock.region()))
                .overrideConfiguration(override -> override
                        .apiCallTimeout(bedrock.timeout())
                        .retryStrategy(retry -> retry.maxAttempts(2)))
                .build();
    }
}
