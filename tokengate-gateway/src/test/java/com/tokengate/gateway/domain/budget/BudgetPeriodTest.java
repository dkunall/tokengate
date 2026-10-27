package com.tokengate.gateway.domain.budget;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetPeriodTest {

    @Test
    void periodsAreUtc() {
        BudgetPeriod p = BudgetPeriod.at(Instant.parse("2026-10-31T23:30:00Z"));
        assertThat(p.day()).isEqualTo("20261031");
        assertThat(p.month()).isEqualTo("202610");
        assertThat(p.untilNextDay()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.untilNextMonth()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void decemberRollsIntoNextYear() {
        BudgetPeriod p = BudgetPeriod.at(Instant.parse("2026-12-15T00:00:00Z"));
        assertThat(p.untilNextMonth()).isEqualTo(Duration.ofDays(17));
    }
}
