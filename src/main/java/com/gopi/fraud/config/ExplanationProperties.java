package com.gopi.fraud.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the explanations of flagged transactions ({@code fraud.explanations.*}). Ollama is used by default;
 * the {@code bedrock} profile switches to Amazon Bedrock. Either way a missing, slow or unhelpful model falls back
 * to the rule reasons.
 */
@ConfigurationProperties(prefix = "fraud.explanations")
public record ExplanationProperties(@DefaultValue Ollama ollama, @DefaultValue Bedrock bedrock) {

    /**
     * @param baseUrl        the Ollama server
     * @param model          an Ollama model tag
     * @param connectTimeout to give up quickly when Ollama isn't running
     * @param timeout        for the whole answer; past it, the rule reasons are used
     * @param maxTokens      Ollama's {@code num_predict}
     */
    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("llama3.2:1b") String model,
            @DefaultValue("500ms") Duration connectTimeout,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("160") int maxTokens) {
    }

    /**
     * @param modelId   any Bedrock model or inference profile id that supports the Converse API
     * @param region    the AWS region the client calls
     * @param timeout   for the whole call, retries included
     * @param maxTokens Converse {@code maxTokens}; a model's reasoning tokens can count toward it
     */
    public record Bedrock(
            @DefaultValue("global.anthropic.claude-haiku-5-5") String modelId,
            @DefaultValue("us-east-1") String region,
            @DefaultValue("10s") Duration timeout,
            @DefaultValue("1024") int maxTokens) {
    }
}
