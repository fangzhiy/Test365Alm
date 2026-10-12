package com.test365alm.server.platform.health;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DatabaseReadinessProbe implements ReadinessChecker {
    private static final Logger log = LoggerFactory.getLogger(DatabaseReadinessProbe.class);
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final String MIGRATION_CHECK = """
            SELECT EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'platform_metadata'
            )
            AND (
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'platform_metadata'
                  AND column_name IN ('metadata_key', 'metadata_value', 'created_at', 'updated_at')
            ) = 4
            AND EXISTS (
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
            )
            AND EXISTS (
                SELECT 1
                FROM flyway_schema_history
                WHERE version = '1' AND success = TRUE
            )
            """;

    private final DataSource dataSource;
    private final int timeoutSeconds;

    public DatabaseReadinessProbe(
            DataSource dataSource,
            @Value("${test365alm.readiness.timeout-ms:2000}") long timeoutMilliseconds) {
        this.dataSource = dataSource;
        this.timeoutSeconds = Math.min(MAX_TIMEOUT_SECONDS,
                Math.max(1, (int) Math.ceil(timeoutMilliseconds / 1000.0)));
    }

    public ReadinessResult check() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(timeoutSeconds)) {
                return ReadinessResult.databaseDown();
            }
            try (PreparedStatement statement = connection.prepareStatement(MIGRATION_CHECK)) {
                statement.setQueryTimeout(timeoutSeconds);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getBoolean(1)) {
                        return ReadinessResult.up();
                    }
                    return ReadinessResult.migrationNotApplied();
                }
            } catch (SQLException exception) {
                // A connection was established, so keep database=UP while
                // reporting that the required structure could not be checked.
                log.warn("Readiness migration check failed: {}", exception.getClass().getSimpleName());
                return ReadinessResult.migrationUnknown();
            }
        } catch (SQLException exception) {
            log.warn("Readiness database check failed: {}", exception.getClass().getSimpleName());
        }
        return ReadinessResult.databaseDown();
    }
}
