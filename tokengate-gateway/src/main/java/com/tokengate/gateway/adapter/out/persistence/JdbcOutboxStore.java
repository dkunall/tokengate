package com.tokengate.gateway.adapter.out.persistence;

import com.tokengate.gateway.application.port.out.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOutboxStore.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcOutboxStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public int drain(int max, Delivery delivery) {
        Integer published = tx.execute(status -> {
            List<Message> batch = jdbc.sql("""
                    select id, partition_key, payload::text as payload from outbox
                    where published_at is null order by id limit ? for update skip locked""")
                    .param(max)
                    .query((rs, n) -> new Message(rs.getLong("id"), rs.getString("partition_key"),
                            rs.getString("payload")))
                    .list();
            int done = 0;
            for (Message m : batch) {
                try {
                    delivery.deliver(m);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("outbox delivery of {} failed: {}", m.id(), e.toString());
                    jdbc.sql("update outbox set attempts = attempts + 1, last_error = ? where id = ?")
                            .params(truncate(e.toString()), m.id()).update();
                    break;
                }
                jdbc.sql("update outbox set published_at = now(), attempts = attempts + 1 where id = ?")
                        .param(m.id()).update();
                done++;
            }
            return done;
        });
        return published == null ? 0 : published;
    }

    @Override
    public long pending() {
        return jdbc.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
