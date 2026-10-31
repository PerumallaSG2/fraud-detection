package com.gopi.fraud.messaging;

/** Topic names. Every topic is keyed by card id, so one card's messages stay on one partition, in order. */
public final class Topics {

    /** Accepted, not yet scored transactions ({@link TransactionSubmitted}). */
    public static final String TRANSACTIONS = "transactions";

    /** One {@link TransactionDecided} per scored transaction, APPROVE included. */
    public static final String DECISIONS = "decisions";

    /** Appended to a topic's name for its dead-letter topic, e.g. {@code transactions.DLT}. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }
}
