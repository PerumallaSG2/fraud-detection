package com.gopi.fraud.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable fraud settings ({@code fraud.*}). Defaults live here; override in application.yml.
 */
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    private int maxScore = 100;
    private Thresholds thresholds = new Thresholds();
    private OddHours oddHours = new OddHours();
    private RiskyMerchant riskyMerchant = new RiskyMerchant();
    private AmountSpike amountSpike = new AmountSpike();

    public int getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(int maxScore) {
        this.maxScore = maxScore;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public OddHours getOddHours() {
        return oddHours;
    }

    public void setOddHours(OddHours oddHours) {
        this.oddHours = oddHours;
    }

    public RiskyMerchant getRiskyMerchant() {
        return riskyMerchant;
    }

    public void setRiskyMerchant(RiskyMerchant riskyMerchant) {
        this.riskyMerchant = riskyMerchant;
    }

    public AmountSpike getAmountSpike() {
        return amountSpike;
    }

    public void setAmountSpike(AmountSpike amountSpike) {
        this.amountSpike = amountSpike;
    }

    /**
     * Fires when the amount exceeds {@code multiplier} times the average of the card's last {@code lookback}
     * non-BLOCK transactions. With fewer than {@code minHistory} of them, it fires above {@code fallbackLimit}.
     */
    public static class AmountSpike {

        private int points = 40;
        private int lookback = 20;
        private int minHistory = 5;
        private BigDecimal multiplier = new BigDecimal("3.0");
        private BigDecimal fallbackLimit = new BigDecimal("5000");

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public int getLookback() {
            return lookback;
        }

        public void setLookback(int lookback) {
            this.lookback = lookback;
        }

        public int getMinHistory() {
            return minHistory;
        }

        public void setMinHistory(int minHistory) {
            this.minHistory = minHistory;
        }

        public BigDecimal getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(BigDecimal multiplier) {
            this.multiplier = multiplier;
        }

        public BigDecimal getFallbackLimit() {
            return fallbackLimit;
        }

        public void setFallbackLimit(BigDecimal fallbackLimit) {
            this.fallbackLimit = fallbackLimit;
        }
    }

    /** Score below {@code review} is APPROVE, above {@code block} is BLOCK, and anything between (inclusive) is REVIEW. */
    public static class Thresholds {

        private int review = 40;
        private int block = 70;

        public int getReview() {
            return review;
        }

        public void setReview(int review) {
            this.review = review;
        }

        public int getBlock() {
            return block;
        }

        public void setBlock(int block) {
            this.block = block;
        }
    }

    /** Fires when the local hour is in [startHour, endHour). */
    public static class OddHours {

        private int points = 20;
        private int startHour = 0;
        private int endHour = 5;

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public int getStartHour() {
            return startHour;
        }

        public void setStartHour(int startHour) {
            this.startHour = startHour;
        }

        public int getEndHour() {
            return endHour;
        }

        public void setEndHour(int endHour) {
            this.endHour = endHour;
        }
    }

    /** Fires when the merchant category is in the list (case-insensitive). */
    public static class RiskyMerchant {

        private int points = 30;
        private List<String> categories = new ArrayList<>(List.of("GAMBLING", "CRYPTO", "WIRE_TRANSFER"));

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public List<String> getCategories() {
            return categories;
        }

        public void setCategories(List<String> categories) {
            this.categories = categories;
        }
    }
}
