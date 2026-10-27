package com.tokengate.metering.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokengate.common.UsageEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeteringEndToEndTest {

    private static final String SECRET = "test-only-jwt-secret-0123456789abcdef";

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        TestInfra.postgres(r);
        r.add("spring.kafka.bootstrap-servers", TestInfra::kafkaBootstrap);
        r.add("tokengate.metering.jwt.hmac-secret", () -> SECRET);
        r.add("tokengate.metering.consumer-group", () -> "metering-it-" + UUID.randomUUID());
    }

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", TestInfra.kafkaBootstrap()))) {
            if (!admin.listTopics().names().get().contains(UsageEvent.TOPIC)) {
                admin.createTopics(List.of(new NewTopic(UsageEvent.TOPIC, 3, (short) 1))).all().get();
            }
        }
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;

    @Test
    void consumesOnceSkipsPoisonAndInvoices() throws Exception {
        String tenant = "t-" + UUID.randomUUID().toString().substring(0, 8);
        UsageEvent a = event(tenant, "mock-alpha", "mock-alpha-small", UsageEvent.CacheOutcome.MISS);
        UsageEvent hit = event(tenant, null, null, UsageEvent.CacheOutcome.EXACT_HIT);
        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(UsageEvent.TOPIC, tenant, json.writeValueAsString(a))).get();
            producer.send(new ProducerRecord<>(UsageEvent.TOPIC, tenant, "{not json")).get();
            producer.send(new ProducerRecord<>(UsageEvent.TOPIC, tenant, json.writeValueAsString(a))).get();
            producer.send(new ProducerRecord<>(UsageEvent.TOPIC, tenant, json.writeValueAsString(hit))).get();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbc.sql(
                        "SELECT coalesce(sum(requests), 0) FROM daily_usage WHERE tenant_id = :t")
                .param("t", tenant).query(Long.class).single()).isEqualTo(2L));

        String period = a.occurredAt().toString().substring(0, 7);
        ResponseEntity<Map> invoice = http.exchange("/admin/invoices", HttpMethod.POST,
                new HttpEntity<>(Map.of("tenant_id", tenant, "period", period), headers("BILLING")), Map.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 1000 prompt tokens at 0.00015/1k plus 500 completion at 0.0006/1k, cache hit free
        assertThat(new java.math.BigDecimal(invoice.getBody().get("total_usd").toString()))
                .isEqualByComparingTo("0.00045");
        assertThat((List<?>) invoice.getBody().get("lines")).hasSize(2);

        ResponseEntity<String> viewer = http.exchange("/admin/invoices", HttpMethod.POST,
                new HttpEntity<>(Map.of("tenant_id", tenant, "period", period), headers("VIEWER")), String.class);
        assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/admin/prices", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders headers(String role) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt(role));
        return h;
    }

    private static String jwt(String role) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"iss\":\"tokengate-dev\",\"sub\":\"it\",\"roles\":[\"" + role
                + "\"],\"iat\":" + now + ",\"exp\":" + (now + 300) + "}").getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = b64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + sig;
    }

    private static KafkaProducer<String, String> producer() {
        Properties p = new Properties();
        p.put("bootstrap.servers", TestInfra.kafkaBootstrap());
        p.put("key.serializer", StringSerializer.class.getName());
        p.put("value.serializer", StringSerializer.class.getName());
        return new KafkaProducer<>(p);
    }

    private static UsageEvent event(String tenant, String provider, String providerModel,
                                     UsageEvent.CacheOutcome cache) {
        return new UsageEvent(1, UUID.randomUUID(), Instant.now(), tenant, "key", "req", "gpt-4o-mini", provider,
                providerModel, 1000, 500, cache, false, 25, UsageEvent.Outcome.OK);
    }
}
