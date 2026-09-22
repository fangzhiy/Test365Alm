package com.test365alm.server.platform.health;

@FunctionalInterface
public interface ReadinessChecker {
    ReadinessResult check();
}
