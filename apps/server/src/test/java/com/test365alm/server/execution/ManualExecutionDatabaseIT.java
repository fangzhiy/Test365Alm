package com.test365alm.server.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import com.test365alm.server.testcase.TestCaseService;

/** Real PostgreSQL proof for the first M09 persisted execution slice. */
@ActiveProfiles("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ManualExecutionDatabaseIT {
    private static final String IMAGE = "postgres:17.11@sha256:f4c66b820c6f974249089d3d16d86a3698eae11e8746eb6644b2271031e91232";
    private static final String RUNTIME_USER = "test365alm_runtime";
    private static final String RUNTIME_PASSWORD = "r03_isolated_test_role_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("m09_it").withUsername("m09_owner").withPassword("m09_owner_password").withInitScript("r03-test-role.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private ExecutionService executions;
    @Autowired private TestCaseService tests;

    @Test
    void createsImmutableManifestExecutesAndRerunsWithoutChangingSource() throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "M09 source", "description", "setup",
                        List.of(new TestCaseService.StepCommand(null, 1, "Open page", "Page is visible"))), "m09-create-001");
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "Smoke", "manual", "m09-set-001");
        assertEquals(set.id(), executions.createSet(f.member(), f.project(), "Smoke", "manual", "m09-set-001").id());
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), "m09-instance-001");
        ExecutionService.RunDetail first = executions.createRun(f.member(), f.project(), instance.id(), "m09-run-001");
        assertEquals(source.currentRevision().title(), first.manifest().title());
        assertEquals(source.currentRevision().steps().get(0).stepKey(), first.manifest().steps().get(0).stepKey());
        ExecutionService.AttemptView saved = executions.saveStep(f.member(), f.project(), first.run().id(), first.currentAttempt().id(),
                first.currentAttempt().steps().get(0).stepKey(), "visible", "PASS", 1, "m09-step-001");
        ExecutionService.AttemptView finished = executions.finish(f.member(), f.project(), first.run().id(), saved.id(), saved.rowVersion(), "m09-finish-001");
        assertEquals("PASS", finished.conclusion());
        ExecutionService.AttemptView retry = executions.rerun(f.member(), f.project(), first.run().id(), "m09-rerun-001");
        assertEquals(2, retry.attemptNo());
        ExecutionService.AttemptView retrySaved = executions.saveStep(f.member(), f.project(), first.run().id(), retry.id(),
                retry.steps().get(0).stepKey(), "visible again", "PASS", 1, "m09-step-002");
        assertEquals("PASS", executions.finish(f.member(), f.project(), first.run().id(), retry.id(), retrySaved.rowVersion(), "m09-finish-002").conclusion());
        assertEquals(3, executions.rerun(f.member(), f.project(), first.run().id(), "m09-rerun-003").attemptNo());
    }

    @Test
    void zeroStepRevisionCannotStartAManualRun() throws Exception {
        Fixture f = fixture();
        TestCaseService.TestCaseView source = tests.create(f.member(), f.project(),
                new TestCaseService.CreateCommand("MANUAL", "Empty", "", "", List.of()), "m09-empty-001");
        ExecutionService.TestSetView set = executions.createSet(f.member(), f.project(), "Empty", "", "m09-empty-set");
        ExecutionService.InstanceView instance = executions.addInstance(f.member(), f.project(), set.id(), source.id(), source.currentRevision().id(), "m09-empty-instance");
        ProjectAccessException error = assertThrows(ProjectAccessException.class, () -> executions.createRun(f.member(), f.project(), instance.id(), "m09-empty-run"));
        assertEquals("NO_EXECUTABLE_STEPS", error.code());
    }

    private Fixture fixture() throws Exception {
        UUID tenant = UUID.randomUUID(), domain = UUID.randomUUID(), project = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(c, "INSERT INTO principal (id, issuer, subject, display_name) VALUES (?, ?, ?, ?)", member, "https://m09.example.invalid", member.toString(), "M09 member");
            execute(c, "INSERT INTO tenant (id, code, name, created_by) VALUES (?, ?, ?, ?)", tenant, "m09-"+tenant.toString().substring(0, 8), "M09 tenant", member);
            execute(c, "INSERT INTO domain (id, tenant_id, name) VALUES (?, ?, ?)", domain, tenant, "Default");
            execute(c, "INSERT INTO tenant_member (tenant_id, principal_id, roles) VALUES (?, ?, ARRAY['MEMBER']::text[])", tenant, member);
            execute(c, "INSERT INTO project (id, tenant_id, domain_id, code, name, created_by) VALUES (?, ?, ?, ?, ?, ?)", project, tenant, domain, "m09-"+project.toString().substring(0, 8), "M09 project", member);
            execute(c, "INSERT INTO project_member (tenant_id, project_id, principal_id, roles) VALUES (?, ?, ?, ARRAY['PROJECT_MEMBER']::text[])", tenant, project, member);
        }
        return new Fixture(tenant, project, member);
    }

    private static void execute(Connection c, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private record Fixture(UUID tenant, UUID project, UUID member) { }
}
