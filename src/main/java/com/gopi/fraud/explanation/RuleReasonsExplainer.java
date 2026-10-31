package com.gopi.fraud.explanation;

import com.gopi.fraud.domain.RuleHit;
import com.gopi.fraud.domain.Transaction;
import java.util.stream.Collectors;

/** The explanation when no model answered usefully: the decision, the score and the triggered rules' reasons. */
public final class RuleReasonsExplainer {

    private RuleReasonsExplainer() {
    }

    /** Rule hits come ordered by points, highest first (as in GET), so the strongest reason leads. */
    public static String explain(Transaction transaction) {
        String reasons = transaction.getRuleHits().stream()
                .map(RuleHit::getReason)
                .collect(Collectors.joining("; "));
        return ExplanationText.cap(("Flagged for %s (score %d): %s.")
                .formatted(transaction.getDecision(), transaction.getRiskScore(), reasons)
                .replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " "));
    }
}
