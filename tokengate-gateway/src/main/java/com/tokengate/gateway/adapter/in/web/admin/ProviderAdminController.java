package com.tokengate.gateway.adapter.in.web.admin;

import com.tokengate.gateway.application.admin.ProviderAdminService;
import com.tokengate.gateway.domain.routing.ProviderId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/providers")
@Tag(name = "Admin: providers")
@SecurityRequirement(name = "adminJwt")
public class ProviderAdminController {

    private final ProviderAdminService providers;

    public ProviderAdminController(ProviderAdminService providers) {
        this.providers = providers;
    }

    @GetMapping
    @Operation(summary = "Configured providers with live breaker state and latency EWMA")
    public List<AdminViews.ProviderView> list() {
        return AdminViews.map(providers.list(), AdminViews.ProviderView::of);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Change weight, priority or enabled; stored in Postgres and applied immediately")
    public AdminViews.ProviderView update(@PathVariable String id, @RequestBody AdminViews.ProviderPatch body) {
        return AdminViews.ProviderView.of(providers.update(ProviderId.of(id), body.weight(), body.priority(),
                body.enabled()));
    }
}
