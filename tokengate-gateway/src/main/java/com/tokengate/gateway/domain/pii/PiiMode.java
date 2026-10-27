package com.tokengate.gateway.domain.pii;

public enum PiiMode {
    /** forward prompts untouched */
    OFF,
    /** replace with a fixed placeholder such as [EMAIL]; the original can't be recovered */
    REDACT,
    /** replace with a keyed token such as &lt;EMAIL_1f2e3d4c&gt; and put the original back in the response */
    TOKENIZE
}
