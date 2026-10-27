package com.tokengate.gateway.application.error;

public final class UnknownModelException extends GatewayException {

    public UnknownModelException(String model) {
        super("model " + model + " is not offered by any configured provider");
    }
}
