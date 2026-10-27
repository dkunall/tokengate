package com.tokengate.mock.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tokengate.mock.domain.Behaviour;
import com.tokengate.mock.domain.FakeUpstream;
import com.tokengate.mock.domain.RecordedRequest;
import com.tokengate.mock.domain.UpstreamRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Test and demo knobs. Not something a real provider has, and not meant to be exposed beyond localhost. */
@RestController
@RequestMapping("/control")
class ControlController {

    private final UpstreamRegistry registry;
    private final ObjectMapper json;

    ControlController(UpstreamRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    record UpstreamView(String name, Behaviour behaviour, long calls, long failures) {
    }

    @GetMapping
    List<UpstreamView> list() {
        return registry.all().stream().map(ControlController::view).toList();
    }

    @GetMapping("/{upstream}/config")
    UpstreamView get(@PathVariable String upstream) {
        return view(find(upstream));
    }

    /** Accepts a partial object, e.g. {"outage": true}; fields not given keep their current value. */
    @RequestMapping(path = "/{upstream}/config", method = {RequestMethod.PUT, RequestMethod.PATCH})
    ResponseEntity<?> configure(@PathVariable String upstream, @RequestBody JsonNode patch) {
        FakeUpstream up = find(upstream);
        ObjectNode merged = json.valueToTree(up.behaviour());
        patch.fields().forEachRemaining(e -> {
            if (merged.has(e.getKey())) {
                merged.set(e.getKey(), e.getValue());
            }
        });
        try {
            up.configure(json.treeToValue(merged, Behaviour.class));
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(root.getMessage())));
        }
        return ResponseEntity.ok(view(up));
    }

    @GetMapping("/{upstream}/requests")
    List<RecordedRequest> requests(@PathVariable String upstream) {
        return find(upstream).recent();
    }

    @PostMapping("/{upstream}/reset")
    UpstreamView reset(@PathVariable String upstream) {
        FakeUpstream up = find(upstream);
        up.reset();
        return view(up);
    }

    @PostMapping("/reset")
    List<UpstreamView> resetAll() {
        registry.all().forEach(FakeUpstream::reset);
        return list();
    }

    private FakeUpstream find(String name) {
        return registry.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown upstream " + name));
    }

    private static UpstreamView view(FakeUpstream up) {
        return new UpstreamView(up.name(), up.behaviour(), up.calls(), up.failures());
    }
}
