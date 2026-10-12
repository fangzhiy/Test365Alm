package com.test365alm.server.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PrincipalRepository {
    private final JdbcTemplate jdbc;

    public PrincipalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Principal upsertVerified(String issuer, String subject, String displayName) {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Verified issuer and subject are required");
        }
        String safeName = displayName == null || displayName.isBlank() ? subject : displayName;
        if (safeName.length() > 200) safeName = safeName.substring(0, 200);
        var rows = jdbc.query("""
                INSERT INTO principal (issuer, subject, display_name)
                VALUES (?, ?, ?)
                ON CONFLICT (issuer, subject) DO UPDATE
                  SET display_name = EXCLUDED.display_name, updated_at = CURRENT_TIMESTAMP
                  WHERE principal.disabled_at IS NULL
                RETURNING id, issuer, subject, display_name, disabled_at
                """, PrincipalRepository::map, issuer, subject, safeName);
        if (rows.isEmpty()) throw new DisabledPrincipalException();
        return rows.get(0);
    }

    public Principal find(String issuer, String subject) {
        try {
            return jdbc.queryForObject("""
                    SELECT id, issuer, subject, display_name, disabled_at FROM principal
                    WHERE issuer = ? AND subject = ?
                    """, PrincipalRepository::map, issuer, subject);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private static Principal map(ResultSet rs, int row) throws SQLException {
        return new Principal(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("display_name"),
                rs.getObject("disabled_at", OffsetDateTime.class) != null);
    }

    public record Principal(UUID id, String issuer, String subject, String displayName, boolean disabled) { }

    public static class DisabledPrincipalException extends RuntimeException {
        public DisabledPrincipalException() { super("Local identity is disabled"); }
    }
}
