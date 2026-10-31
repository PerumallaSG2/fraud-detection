package com.gopi.fraud.explanation;

import com.gopi.fraud.config.ExplanationProperties;
import com.gopi.fraud.domain.ExplanationSource;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * A model on Amazon Bedrock through the Converse API, which works the same for every Converse model, so switching
 * models is only {@code fraud.explanations.bedrock.model-id}. No model-specific fields are sent (no temperature: some
 * models, such as Claude Haiku 5.5, reject it). Only text blocks are read; reasoning blocks are skipped. Any stop
 * other than the end of the turn (cut off, filtered, refused) counts as no answer.
 */
@Component
@Profile("bedrock")
public class BedrockExplanationProvider implements ExplanationProvider {

    private final BedrockRuntimeClient bedrock;
    private final ExplanationProperties.Bedrock config;

    public BedrockExplanationProvider(BedrockRuntimeClient bedrock, ExplanationProperties properties) {
        this.bedrock = bedrock;
        this.config = properties.bedrock();
    }

    @Override
    public ExplanationSource source() {
        return ExplanationSource.BEDROCK;
    }

    @Override
    public String model() {
        return config.modelId();
    }

    @Override
    public String explain(ExplanationPrompt prompt) {
        ConverseResponse response = bedrock.converse(request -> request
                .modelId(config.modelId())
                .system(SystemContentBlock.fromText(prompt.system()))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(prompt.user()))
                        .build())
                .inferenceConfig(inference -> inference.maxTokens(config.maxTokens())));
        if (response.stopReason() != StopReason.END_TURN) {
            throw new IllegalStateException("Bedrock stopped with " + response.stopReasonAsString());
        }
        return response.output().message().content().stream()
                .map(ContentBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
    }
}
