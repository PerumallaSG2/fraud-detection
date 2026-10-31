package com.gopi.fraud.exception;

/** Kafka did not acknowledge a message in time, so it may not have been stored. */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String topic, Throwable cause) {
        super("Could not publish to " + topic, cause);
    }
}
