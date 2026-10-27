package com.tokengate.metering.application;

import com.tokengate.common.UsageEvent;
import com.tokengate.metering.application.port.UsageLedger;
import com.tokengate.metering.domain.UsageCharge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UsageIngestion {

    public enum Result { APPLIED, DUPLICATE, UNSUPPORTED_SCHEMA }

    private static final Logger log = LoggerFactory.getLogger(UsageIngestion.class);

    private final UsageLedger ledger;
    private final PriceBookCache prices;

    public UsageIngestion(UsageLedger ledger, PriceBookCache prices) {
        this.ledger = ledger;
        this.prices = prices;
    }

    public Result ingest(UsageEvent event) {
        if (event.schemaVersion() > UsageEvent.CURRENT_SCHEMA) {
            // A newer gateway may add fields we would silently drop; better to leave it unbilled and visible.
            log.warn("skipping usage event {} with schema {}", event.eventId(), event.schemaVersion());
            return Result.UNSUPPORTED_SCHEMA;
        }
        UsageCharge charge = UsageCharge.of(event, prices.current());
        if (!charge.priced()) {
            log.warn("no price for {}/{} at {}; recorded at zero cost", event.provider(), event.providerModel(),
                    event.occurredAt());
        }
        return ledger.applyOnce(event.eventId(), event.occurredAt(), charge) ? Result.APPLIED : Result.DUPLICATE;
    }
}
