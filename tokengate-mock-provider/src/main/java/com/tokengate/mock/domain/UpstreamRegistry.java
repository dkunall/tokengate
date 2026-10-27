package com.tokengate.mock.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class UpstreamRegistry {

    private final Map<String, FakeUpstream> upstreams = new LinkedHashMap<>();

    public UpstreamRegistry(Map<String, Behaviour> initial) {
        initial.forEach((name, b) -> upstreams.put(name, new FakeUpstream(name, b)));
    }

    public Optional<FakeUpstream> find(String name) {
        return Optional.ofNullable(upstreams.get(name));
    }

    public Collection<FakeUpstream> all() {
        return upstreams.values();
    }
}
