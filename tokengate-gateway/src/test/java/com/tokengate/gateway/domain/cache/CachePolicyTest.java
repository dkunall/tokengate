package com.tokengate.gateway.domain.cache;

import com.tokengate.gateway.domain.chat.ChatMessage;
import com.tokengate.gateway.domain.chat.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CachePolicyTest {

    private static ChatRequest req(Double temp, boolean stream) {
        return new ChatRequest("m", List.of(ChatMessage.user("hi")), temp, null, null, null, stream, null);
    }

    @Test
    void defaultsOnlyCacheDeterministicNonStreamedCalls() {
        CachePolicy p = CachePolicy.defaults();
        assertThat(p.decide(req(0.0, false))).isEqualTo(CachePolicy.Decision.CACHEABLE);
        assertThat(p.decide(req(null, false))).isEqualTo(CachePolicy.Decision.BYPASS_NON_DETERMINISTIC);
        assertThat(p.decide(req(0.7, false))).isEqualTo(CachePolicy.Decision.BYPASS_NON_DETERMINISTIC);
        assertThat(p.decide(req(0.0, true))).isEqualTo(CachePolicy.Decision.BYPASS_STREAM);
    }

    @Test
    void tenantCanOptIn() {
        CachePolicy p = new CachePolicy(true, true, 0.9, Duration.ofMinutes(5), true, true);
        assertThat(p.decide(req(0.7, true))).isEqualTo(CachePolicy.Decision.CACHEABLE);
        CachePolicy off = new CachePolicy(false, true, 0.9, Duration.ofMinutes(5), true, true);
        assertThat(off.decide(req(0.0, false))).isEqualTo(CachePolicy.Decision.BYPASS_DISABLED);
    }
}
