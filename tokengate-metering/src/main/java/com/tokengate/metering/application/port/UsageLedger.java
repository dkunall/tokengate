package com.tokengate.metering.application.port;

import com.tokengate.metering.domain.DailyUsage;
import com.tokengate.metering.domain.UsageCharge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface UsageLedger {

    /**
     * Records the event id and adds the charge to its daily row atomically.
     *
     * @return false when the event id was already processed, in which case nothing changes
     */
    boolean applyOnce(UUID eventId, Instant occurredAt, UsageCharge charge);

    List<DailyUsage> daily(String tenantId, LocalDate from, LocalDate toInclusive);
}
