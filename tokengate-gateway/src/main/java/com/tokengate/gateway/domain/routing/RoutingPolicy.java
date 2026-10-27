package com.tokengate.gateway.domain.routing;

public enum RoutingPolicy {
    /** cheapest estimated cost for this request first */
    COST,
    /** lowest observed latency (EWMA) first */
    LATENCY,
    /** smooth weighted round robin over provider weights */
    WEIGHTED,
    /** fixed order by priority, lowest number first */
    PRIORITY,
    /** consistent-hash the caller's session/user onto a provider so a conversation stays on one */
    STICKY
}
