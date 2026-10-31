package com.gopi.fraud.explanation;

import com.gopi.fraud.domain.Decision;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cleans a model's answer and rejects one that can't be shown: blank, with a link, or contradicting the decision
 * (a model only explains it). A rejected answer is replaced by the rule reasons.
 */
public final class ExplanationText {

    /** Fits {@code alert_explanations.text}. */
    public static final int MAX_CHARS = 500;

    private static final Pattern URL = Pattern.compile("(?i)https?://|www\\.");
    private static final Pattern APPROVE = Pattern.compile("(?i)\\bapprov");
    private static final Pattern BLOCKED = Pattern.compile("(?i)\\bblock(ed|ing)?\\b");

    private ExplanationText() {
    }

    public static Optional<String> clean(String raw, Decision decision) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = cap(raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip());
        if (text.isEmpty() || URL.matcher(text).find() || APPROVE.matcher(text).find()
                || (decision == Decision.REVIEW && BLOCKED.matcher(text).find())) {
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /** Up to {@link #MAX_CHARS}: cut after the last full sentence, else the last word, and mark the cut. */
    static String cap(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        String head = text.substring(0, MAX_CHARS - 1);
        int sentence = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (sentence > MAX_CHARS / 2) {
            return head.substring(0, sentence + 1);
        }
        int word = head.lastIndexOf(' ');
        return (word > 0 ? head.substring(0, word) : head) + "…";
    }
}
