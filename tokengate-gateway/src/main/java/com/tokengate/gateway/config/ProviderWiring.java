package com.tokengate.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.gateway.adapter.out.provider.AnthropicProvider;
import com.tokengate.gateway.adapter.out.provider.GeminiProvider;
import com.tokengate.gateway.adapter.out.provider.OpenAiCompatibleProvider;
import com.tokengate.gateway.application.port.out.ChatProvider;
import com.tokengate.gateway.domain.routing.ModelOffer;
import com.tokengate.gateway.domain.routing.Price;
import com.tokengate.gateway.domain.routing.ProviderDescriptor;
import com.tokengate.gateway.domain.routing.ProviderId;
import com.tokengate.gateway.domain.routing.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns the provider list in configuration into descriptors (for routing) and adapters (for calling). */
final class ProviderWiring {

    private static final Logger log = LoggerFactory.getLogger(ProviderWiring.class);

    record Wired(List<ProviderDescriptor> descriptors, List<ChatProvider> adapters) {
    }

    private ProviderWiring() {
    }

    static Wired wire(GatewayProperties props, HttpClient http, ObjectMapper json) {
        List<ProviderDescriptor> descriptors = new ArrayList<>();
        List<ChatProvider> adapters = new ArrayList<>();
        Duration idle = props.routing().streamIdleTimeout();
        for (GatewayProperties.Provider p : props.providers()) {
            ProviderType type = ProviderType.valueOf(p.type().toUpperCase(Locale.ROOT));
            boolean hasKey = p.apiKey() != null && !p.apiKey().isBlank();
            if (type != ProviderType.MOCK && !hasKey) {
                log.info("provider {} ({}) has no API key configured, leaving it out", p.id(), type);
                continue;
            }
            ProviderId id = ProviderId.of(p.id());
            descriptors.add(new ProviderDescriptor(id, type, offers(p.models()), p.weight(), p.priority(),
                    p.enabled(), p.timeout()));
            adapters.add(switch (type) {
                case MOCK, OPENAI -> new OpenAiCompatibleProvider(id, p.baseUrl(), p.apiKey(), p.embeddings(), http,
                        json, idle);
                case GEMINI -> new GeminiProvider(id, p.baseUrl(), p.apiKey(), http, json, idle);
                case ANTHROPIC -> new AnthropicProvider(id, p.baseUrl(), p.apiKey(), http, json, idle);
            });
            log.info("provider {} ({}) enabled with models {}", id, type, p.models().keySet());
        }
        return new Wired(descriptors, adapters);
    }

    private static Map<String, ModelOffer> offers(Map<String, GatewayProperties.Model> models) {
        Map<String, ModelOffer> out = new LinkedHashMap<>();
        if (models != null) {
            models.forEach((logical, m) -> out.put(logical,
                    new ModelOffer(m.nativeModel(), new Price(micros(m.inputPer1k()), micros(m.outputPer1k())))));
        }
        return out;
    }

    private static long micros(BigDecimal usd) {
        return usd == null ? 0 : usd.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
