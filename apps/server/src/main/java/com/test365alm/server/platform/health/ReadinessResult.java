package com.test365alm.server.platform.health;

public record ReadinessResult(boolean ready, String database, String migration) {
    public static ReadinessResult up() {
        return new ReadinessResult(true, "UP", "APPLIED");
    }

    public static ReadinessResult down() {
        return new ReadinessResult(false, "DOWN", "UNKNOWN");
    }
}
