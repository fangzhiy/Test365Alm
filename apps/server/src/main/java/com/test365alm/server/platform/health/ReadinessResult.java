package com.test365alm.server.platform.health;

public record ReadinessResult(boolean ready, String database, String migration) {
    public static ReadinessResult up() {
        return new ReadinessResult(true, "UP", "APPLIED");
    }

    public static ReadinessResult databaseDown() {
        return new ReadinessResult(false, "DOWN", "UNKNOWN");
    }

    public static ReadinessResult migrationNotApplied() {
        return new ReadinessResult(false, "UP", "NOT_APPLIED");
    }

    public static ReadinessResult migrationUnknown() {
        return new ReadinessResult(false, "UP", "UNKNOWN");
    }

    /**
     * Kept as a source-compatible alias for callers that only need the
     * database-unavailable result.
     */
    public static ReadinessResult down() {
        return databaseDown();
    }
}
