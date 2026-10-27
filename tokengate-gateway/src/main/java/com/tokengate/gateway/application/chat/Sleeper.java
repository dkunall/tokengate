package com.tokengate.gateway.application.chat;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

    void sleep(Duration d) throws InterruptedException;

    Sleeper REAL = d -> Thread.sleep(d);
}
