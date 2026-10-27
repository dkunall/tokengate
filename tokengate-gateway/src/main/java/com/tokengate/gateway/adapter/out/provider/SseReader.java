package com.tokengate.gateway.adapter.out.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minimal text/event-stream parser: "event:" and "data:" fields, events separated by a blank line. */
final class SseReader implements AutoCloseable {

    record Event(String name, String data) {
    }

    private final InputStream in;
    private final BufferedReader reader;

    SseReader(InputStream in) {
        this.in = in;
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** @return the next event, or null at end of stream */
    Event next() throws IOException {
        String name = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    return new Event(name, data.toString());
                }
                name = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> name = value;
                case "data" -> {
                    if (data == null) {
                        data = new StringBuilder(value);
                    } else {
                        data.append('\n').append(value);
                    }
                }
                default -> {
                }
            }
        }
        return data == null ? null : new Event(name, data.toString());
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing is how we abort the upstream; a failure here changes nothing for the caller
        }
    }
}
