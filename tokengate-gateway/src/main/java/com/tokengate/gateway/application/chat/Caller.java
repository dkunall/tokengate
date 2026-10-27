package com.tokengate.gateway.application.chat;

import com.tokengate.gateway.domain.tenant.Tenant;

/** An authenticated API key holder. */
public record Caller(Tenant tenant, String apiKeyId) {

    public String tenantId() {
        return tenant.id().value();
    }
}
