package com.tokengate.gateway.domain.pii;

/**
 * Unmasks a streamed response. A token can arrive split across chunks ("&lt;EMA" then "IL_1f2e3d4c&gt;"), so the
 * tail of the buffer starting at an unclosed '&lt;' is held back until it either closes or grows longer than any
 * token could be. At most {@link MaskingSession#MAX_TOKEN_LENGTH} characters are ever delayed.
 */
public final class StreamingUnmasker {

    private final MaskingSession session;
    private final StringBuilder pending = new StringBuilder();

    StreamingUnmasker(MaskingSession session) {
        this.session = session;
    }

    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        int open = pending.lastIndexOf("<");
        boolean holdTail = open >= 0
                && pending.indexOf(">", open) < 0
                && pending.length() - open < MaskingSession.MAX_TOKEN_LENGTH;
        String ready;
        if (holdTail) {
            ready = pending.substring(0, open);
            pending.delete(0, open);
        } else {
            ready = pending.toString();
            pending.setLength(0);
        }
        return session.unmask(ready);
    }

    public String finish() {
        String rest = session.unmask(pending.toString());
        pending.setLength(0);
        return rest;
    }
}
