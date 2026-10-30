package com.gopi.fraud.dto;

import java.util.List;

/** One page of alerts, newest first. Our own shape rather than Spring's {@code Page}, whose JSON isn't stable. */
public record AlertPageResponse(List<AlertResponse> alerts, int page, int size, long totalElements, int totalPages) {
}
