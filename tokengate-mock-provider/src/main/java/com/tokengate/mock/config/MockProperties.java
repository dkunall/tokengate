package com.tokengate.mock.config;

import com.tokengate.mock.domain.Behaviour;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "mock")
public record MockProperties(Map<String, Behaviour> upstreams, Long seed) {
}
