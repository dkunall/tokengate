package com.tokengate.gateway.application.error;

/** Expected, client-visible failures. The web adapter maps each subtype to a status and a problem type. */
public abstract sealed class GatewayException extends RuntimeException
        permits RateLimitedException, BudgetExceededException, NoProviderAvailableException, UpstreamFailedException,
        UnknownModelException, NotFoundException, ConflictException {

    protected GatewayException(String message) {
        super(message);
    }
}
