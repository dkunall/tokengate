package com.tokengate.metering.adapter.in.web;

import com.tokengate.metering.adapter.in.web.MeteringViews.DailyUsageView;
import com.tokengate.metering.adapter.in.web.MeteringViews.InvoiceRequest;
import com.tokengate.metering.adapter.in.web.MeteringViews.InvoiceView;
import com.tokengate.metering.adapter.in.web.MeteringViews.PriceView;
import com.tokengate.metering.adapter.in.web.MeteringViews.UsageReport;
import com.tokengate.metering.application.InvoiceService;
import com.tokengate.metering.application.PriceBookCache;
import com.tokengate.metering.application.port.UsageLedger;
import com.tokengate.metering.domain.BillingPeriod;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/admin")
class MeteringAdminController {

    private static final long MAX_REPORT_DAYS = 366;

    private final UsageLedger ledger;
    private final PriceBookCache prices;
    private final InvoiceService invoices;

    MeteringAdminController(UsageLedger ledger, PriceBookCache prices, InvoiceService invoices) {
        this.ledger = ledger;
        this.prices = prices;
        this.invoices = invoices;
    }

    @GetMapping("/usage/{tenantId}")
    UsageReport usage(@PathVariable String tenantId,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAX_REPORT_DAYS) {
            throw new IllegalArgumentException("from must be on or before to, and at most a year apart");
        }
        List<DailyUsageView> days = ledger.daily(tenantId, from, to).stream().map(DailyUsageView::of).toList();
        return new UsageReport(tenantId, from, to, days);
    }

    @GetMapping("/prices")
    List<PriceView> prices() {
        return prices.current().all().stream().map(PriceView::of).toList();
    }

    @PostMapping("/prices")
    ResponseEntity<PriceView> addPrice(@RequestBody PriceView body) {
        prices.append(body.toDomain());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/invoices")
    InvoiceView generate(@RequestBody InvoiceRequest body) {
        return InvoiceView.of(invoices.generate(requireTenant(body.tenantId()), BillingPeriod.parse(body.period())));
    }

    @PostMapping("/invoices/{tenantId}/{period}/issue")
    InvoiceView issue(@PathVariable String tenantId, @PathVariable String period) {
        return InvoiceView.of(invoices.issue(tenantId, BillingPeriod.parse(period)));
    }

    @GetMapping("/invoices/{tenantId}")
    List<InvoiceView> list(@PathVariable String tenantId) {
        return invoices.forTenant(tenantId).stream().map(InvoiceView::of).toList();
    }

    @GetMapping("/invoices/{tenantId}/{period}")
    ResponseEntity<InvoiceView> get(@PathVariable String tenantId, @PathVariable String period) {
        return invoices.find(tenantId, BillingPeriod.parse(period))
                .map(InvoiceView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenant_id is required");
        }
        return tenantId;
    }
}
