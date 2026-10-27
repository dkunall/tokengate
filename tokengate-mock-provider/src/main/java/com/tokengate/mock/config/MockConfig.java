package com.tokengate.mock.config;

import com.tokengate.mock.domain.Behaviour;
import com.tokengate.mock.domain.UpstreamRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

@Configuration
@EnableConfigurationProperties(MockProperties.class)
class MockConfig {

    @Bean
    UpstreamRegistry upstreamRegistry(MockProperties props) {
        Map<String, Behaviour> initial = props.upstreams() == null || props.upstreams().isEmpty()
                ? Map.of("alpha", Behaviour.healthy(120), "beta", Behaviour.healthy(40))
                : props.upstreams();
        return new UpstreamRegistry(initial);
    }

    @Bean
    RandomGenerator mockRandom(MockProperties props) {
        RandomGenerator base = props.seed() == null ? new SplittableRandom() : new SplittableRandom(props.seed());
        return new SynchronizedRandom(base);
    }

    /** SplittableRandom is not thread-safe; requests arrive on many virtual threads. */
    private record SynchronizedRandom(RandomGenerator delegate) implements RandomGenerator {
        @Override
        public synchronized long nextLong() {
            return delegate.nextLong();
        }
    }
}
