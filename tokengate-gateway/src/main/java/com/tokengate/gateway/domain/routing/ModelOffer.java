package com.tokengate.gateway.domain.routing;

/** What one provider calls a logical model, and what it charges for it. */
public record ModelOffer(String nativeModel, Price price) {
}
