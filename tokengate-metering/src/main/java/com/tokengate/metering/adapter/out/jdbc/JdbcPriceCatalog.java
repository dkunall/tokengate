package com.tokengate.metering.adapter.out.jdbc;

import com.tokengate.metering.application.port.PriceCatalog;
import com.tokengate.metering.domain.PriceVersion;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;

public final class JdbcPriceCatalog implements PriceCatalog {

    private final JdbcClient jdbc;

    public JdbcPriceCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PriceVersion> all() {
        return jdbc.sql("""
                        SELECT provider, provider_model, input_per_1k_usd, output_per_1k_usd, effective_from
                        FROM model_prices""")
                .query((rs, n) -> new PriceVersion(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getTimestamp(5).toInstant()))
                .list();
    }

    @Override
    public void append(PriceVersion v) {
        jdbc.sql("""
                        INSERT INTO model_prices (provider, provider_model, input_per_1k_usd, output_per_1k_usd,
                                                  effective_from)
                        VALUES (:provider, :model, :in, :out, :from)""")
                .param("provider", v.provider())
                .param("model", v.providerModel())
                .param("in", v.inputPer1k())
                .param("out", v.outputPer1k())
                .param("from", Timestamp.from(v.effectiveFrom()))
                .update();
    }
}
