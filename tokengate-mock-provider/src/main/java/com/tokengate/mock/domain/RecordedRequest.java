package com.tokengate.mock.domain;

import java.time.Instant;
import java.util.List;

public record RecordedRequest(Instant at, String endpoint, String model, List<String> contents, boolean stream) {
}
