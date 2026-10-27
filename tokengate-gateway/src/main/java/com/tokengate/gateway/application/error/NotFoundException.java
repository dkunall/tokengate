package com.tokengate.gateway.application.error;

public final class NotFoundException extends GatewayException {

    public NotFoundException(String what) {
        super(what + " not found");
    }
}
