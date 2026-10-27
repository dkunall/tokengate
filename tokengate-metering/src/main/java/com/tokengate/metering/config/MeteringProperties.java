package com.tokengate.metering.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("tokengate.metering")
public record MeteringProperties(String consumerGroup, Duration priceCacheTtl, Duration retryBackoff, Jwt jwt) {

    public record Jwt(String issuer, String issuerUri, String hmacSecret) {
    }
}
