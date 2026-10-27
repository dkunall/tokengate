package com.tokengate.gateway.adapter.in.web.admin;

import com.tokengate.gateway.application.admin.ApiKeyAdminService;
import com.tokengate.gateway.application.admin.TenantAdminService;
import com.tokengate.gateway.domain.tenant.TenantId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

@RestController
@RequestMapping("/admin/tenants")
@Tag(name = "Admin: tenants and keys")
@SecurityRequirement(name = "adminJwt")
public class TenantAdminController {

    private final TenantAdminService tenants;
    private final ApiKeyAdminService keys;
    private final Clock clock;

    public TenantAdminController(TenantAdminService tenants, ApiKeyAdminService keys, Clock clock) {
        this.tenants = tenants;
        this.keys = keys;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant; omitted limits get conservative defaults")
    public AdminViews.TenantView create(@RequestBody TenantRequest body) {
        return AdminViews.TenantView.of(tenants.create(body.create(clock.instant())));
    }

    @GetMapping
    public List<AdminViews.TenantView> list() {
        return AdminViews.map(tenants.list(), AdminViews.TenantView::of);
    }

    @GetMapping("/{id}")
    public AdminViews.TenantView get(@PathVariable String id) {
        return AdminViews.TenantView.of(tenants.get(TenantId.of(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change limits, routing, cache or PII policy; null fields are left alone")
    public AdminViews.TenantView update(@PathVariable String id, @RequestBody TenantRequest body) {
        return AdminViews.TenantView.of(tenants.update(TenantId.of(id), body::applyTo));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Deactivate a tenant; its keys stop working once the key cache expires")
    public AdminViews.TenantView deactivate(@PathVariable String id) {
        return AdminViews.TenantView.of(tenants.deactivate(TenantId.of(id)));
    }

    @GetMapping("/{id}/budget")
    public AdminViews.BudgetView budget(@PathVariable String id) {
        return AdminViews.BudgetView.of(tenants.budget(TenantId.of(id)));
    }

    @PostMapping("/{id}/keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue an API key; the plaintext is in this response only")
    public AdminViews.KeyView issueKey(@PathVariable String id, @RequestBody(required = false) AdminViews.KeyRequest body) {
        String name = body == null || body.name() == null ? "default" : body.name();
        return AdminViews.KeyView.issued(keys.issue(TenantId.of(id), name));
    }

    @GetMapping("/{id}/keys")
    public List<AdminViews.KeyView> listKeys(@PathVariable String id) {
        return AdminViews.map(keys.list(TenantId.of(id)), AdminViews.KeyView::of);
    }

    @DeleteMapping("/{id}/keys/{keyId}")
    public AdminViews.KeyView revoke(@PathVariable String id, @PathVariable String keyId) {
        return AdminViews.KeyView.of(keys.revoke(TenantId.of(id), keyId));
    }
}
