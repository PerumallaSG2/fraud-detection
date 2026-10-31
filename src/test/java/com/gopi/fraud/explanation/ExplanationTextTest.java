package com.gopi.fraud.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gopi.fraud.domain.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplanationTextTest {

    @Test
    void collapsesWhitespaceAndControlCharacters() {
        assertThat(ExplanationText.clean("  Gambling\nmerchant\tat 02:30.\u0007  ",
                Decision.REVIEW)).contains("Gambling merchant at 02:30.");
    }

    @Test
    void capsAt500CharactersAfterTheLastFullSentence() {
        String sentence = "This sentence is exactly fifty characters long ok. ";
        String text = ExplanationText.clean(sentence.repeat(12), Decision.REVIEW).orElseThrow();

        assertThat(text).hasSizeLessThanOrEqualTo(ExplanationText.MAX_CHARS).endsWith("ok.");
    }

    @Test
    void capsAtAWordWhenThereIsNoSentenceToCutAfter() {
        String text = ExplanationText.clean("word ".repeat(200), Decision.REVIEW).orElseThrow();

        assertThat(text).hasSizeLessThanOrEqualTo(ExplanationText.MAX_CHARS).endsWith("word…");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "See https://example.com for details.", "Visit www.example.com.",
            "This transaction should be approved.", "Approval is recommended."})
    void rejectsBlankLinksAndApproval(String answer) {
        assertThat(ExplanationText.clean(answer, Decision.BLOCK)).isEmpty();
    }

    @Test
    void rejectsSayingBlockedForAReviewButNotForABlock() {
        String answer = "The transaction was blocked because it was at a casino at night.";

        assertThat(ExplanationText.clean(answer, Decision.REVIEW)).isEmpty();
        assertThat(ExplanationText.clean(answer, Decision.BLOCK)).contains(answer);
    }

    @Test
    void rejectsNoAnswer() {
        assertThat(ExplanationText.clean(null, Decision.REVIEW)).isEmpty();
    }
}
