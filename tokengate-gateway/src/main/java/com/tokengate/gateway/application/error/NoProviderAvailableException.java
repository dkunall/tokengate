package com.tokengate.gateway.application.error;

public final class NoProviderAvailableException extends GatewayException {

    public NoProviderAvailableException(String model) {
        super("no healthy provider serves model " + model);
    }
}
