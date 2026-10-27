package com.tokengate.gateway.domain.budget;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Budgets reset at 00:00 UTC (daily tokens) and on the 1st of the month UTC (cost cap), whatever the tenant's zone. */
public record BudgetPeriod(String day, String month, Duration untilNextDay, Duration untilNextMonth) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    public static BudgetPeriod at(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant nextDay = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new BudgetPeriod(today.format(DAY), today.format(MONTH),
                Duration.between(now, nextDay), Duration.between(now, nextMonth));
    }
}
