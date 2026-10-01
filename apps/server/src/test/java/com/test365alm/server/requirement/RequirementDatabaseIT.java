package com.test365alm.server.requirement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL proof for requirement revisions, CAS and idempotency. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Timeout(180)
class RequirementDatabaseIT {
    private static final String IMAGE =
            "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("requirement_it")
            .withUsername("requirement_owner")
            .withPassword("requirement_owner_password")
            .withInitScript("r03-test-role.sql");

    @Autowired
    private RequirementService requirements;
    @Autowired
    private ProjectService projects;
    @Autowired
    private JdbcTemplate runtimeJdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    @Test
    void memberCanCreateEditAndReadImmutableHistory() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);

        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("First title", "first body", "HIGH"), "create-key-" + UUID.randomUUID());
        assertEquals(1, created.displayNumber());
        assertEquals(1, created.revisionNumber());
        assertEquals("HIGH", created.priority());

        RequirementService.RequirementView updated = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Second title", null, null),
                etag(created), "update-key-" + UUID.randomUUID());
        assertEquals(2, updated.revisionNumber());
        assertEquals("first body", updated.body());
        assertEquals("Second title", updated.title());
        assertEquals(2, requirements.revisions(fixture.member(), fixture.project(), created.id()).size());
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM outbox_event WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
        assertEquals(2, ownerCount("SELECT COUNT(*) FROM audit_event WHERE object_id = ?", created.id()));
    }

    @Test
    void viewerReadsButCannotWriteAndStaleVersionDoesNotCreateRevision() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        projects.putMember(fixture.admin(), fixture.project(), fixture.viewer(), List.of("PROJECT_VIEWER"), 0L, false);
        RequirementService.RequirementView created = requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("A title", "A body", null), "create-key-" + UUID.randomUUID());

        assertEquals(1, requirements.list(fixture.viewer(), fixture.project(), null, null).items().size());
        assertThrows(ProjectAccessException.class, () -> requirements.create(fixture.viewer(), fixture.project(),
                new RequirementService.CreateCommand("Denied", "", null), "create-key-" + UUID.randomUUID()));
        RequirementService.RequirementView updated = requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("B title", null, null), etag(created),
                "update-key-" + UUID.randomUUID());
        assertEquals(2, updated.revisionNumber());
        int revisions = ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id());
        assertThrows(ProjectAccessException.class, () -> requirements.update(fixture.member(), fixture.project(), created.id(),
                new RequirementService.UpdateCommand("Lost title", null, null), etag(created),
                "update-key-" + UUID.randomUUID()));
        assertEquals(revisions, ownerCount("SELECT COUNT(*) FROM requirement_revision WHERE requirement_id = ?", created.id()));
    }

    @Test
    void sameCreateKeyIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        Fixture fixture = fixture();
        projects.putMember(fixture.admin(), fixture.project(), fixture.member(), List.of("PROJECT_MEMBER"), 0L, false);
        String key = "create-key-" + UUID.randomUUID();
        RequirementService.CreateCommand command = new RequirementService.CreateCommand("Same", "Body", "MEDIUM");
        RequirementService.RequirementView first = requirements.create(fixture.member(), fixture.project(), command, key);
        RequirementService.RequirementView replay = requirements.create(fixture.member(), fixture.project(), command, key);
        assertEquals(first.id(), replay.id());
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
        assertThrows(ProjectAccessException.class, () -> requirements.create(fixture.member(), fixture.project(),
                new RequirementService.CreateCommand("Different", "Body", "MEDIUM"), key));
        assertEquals(1, ownerCount("SELECT COUNT(*) FROM requirement WHERE project_id = ?", fixture.project()));
    }

    private Fixture fixture() throws SQLException {
        UUID admin = principal("admin");
        UUID member = principal("member");
        UUID viewer = principal("viewer");
        UUID tenant = UUID.randomUUID();
        UUID domain = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", tenant,
                    "req-tenant-" + tenant, "Requirement Tenant", admin);
            execute(connection, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, tenant,
                    "Requirement Domain");
            for (UUID principal : List.of(admin, member, viewer)) {
                execute(connection, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ?::text[])",
                        tenant, principal, principal.equals(admin) ? "{TENANT_ADMIN}" : "{MEMBER}");
            }
        }
        ProjectService.ProjectView project = projects.createProject(admin, tenant, domain,
                "REQ-" + UUID.randomUUID(), "Requirement Project");
        return new Fixture(admin, member, viewer, tenant, project.id());
    }

    private UUID principal(String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = ownerConnection()) {
            execute(connection, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)",
                    id, "https://requirement-it.example/realm", kind + "-" + id, kind);
        }
        return id;
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private int ownerCount(String sql, Object... args) {
        try (Connection ignored = ownerConnection()) {
            return new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                            new org.postgresql.Driver(), POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                    .queryForObject(sql, Integer.class, args);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String etag(RequirementService.RequirementView view) {
        return "\"" + view.rowVersion() + "\"";
    }

    private record Fixture(UUID admin, UUID member, UUID viewer, UUID tenant, UUID project) { }
}
