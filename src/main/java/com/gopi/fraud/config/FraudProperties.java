package com.gopi.fraud.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunable fraud settings ({@code fraud.*}). Defaults live here; override in application.yml.
 */
@ConfigurationProperties(prefix = "fraud")
public class FraudProperties {

    private OddHours oddHours = new OddHours();
    private RiskyMerchant riskyMerchant = new RiskyMerchant();

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
