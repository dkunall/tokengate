package com.tokengate.metering.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** A calendar month in UTC. Invoices are per tenant per period. */
public record BillingPeriod(YearMonth month) {

    public BillingPeriod {
        Objects.requireNonNull(month, "month");
    }

    public static BillingPeriod parse(String value) {
        try {
            return new BillingPeriod(YearMonth.parse(value));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("period must look like 2026-10, got " + value);
        }
    }

    public static BillingPeriod containing(LocalDate day) {
        return new BillingPeriod(YearMonth.from(day));
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public LocalDate lastDay() {
        return month.atEndOfMonth();
    }

    @Override
    public String toString() {
        return month.toString();
    }
}
