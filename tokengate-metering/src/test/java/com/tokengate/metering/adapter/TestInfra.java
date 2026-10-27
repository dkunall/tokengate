package com.tokengate.metering.adapter;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/** One Postgres and one Kafka for the whole test JVM; started lazily, stopped by Ryuk. */
public final class TestInfra {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static GenericContainer<?> kafka;
    private static int kafkaPort;

    static {
        POSTGRES.start();
    }

    public static void postgres(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The apache/kafka image has to advertise the host port before it starts, which the Testcontainers 1.19
     * KafkaContainer cannot do for that image, so this picks a free port and pins it.
     */
    @SuppressWarnings({"deprecation", "resource"})
    public static synchronized String kafkaBootstrap() {
        if (kafka == null) {
            kafkaPort = freePort();
            kafka = new FixedHostPortGenericContainer<>("apache/kafka:3.8.0")
                    .withFixedExposedPort(kafkaPort, 9092)
                    .withEnv("KAFKA_NODE_ID", "1")
                    .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                    .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + kafkaPort)
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_HEAP_OPTS", "-Xmx256m -Xms256m")
                    .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            kafka.start();
        }
        return "localhost:" + kafkaPort;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private TestInfra() {
    }
}
