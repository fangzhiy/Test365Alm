package com.test365alm.server.fixture;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import com.test365alm.server.execution.ExecutionService;

/** Assertions after the original target/snapshot checks, never a historical business implementation. */
public final class HistoricalExecutionUpgradeSupport {
    private HistoricalExecutionUpgradeSupport() { }

    /** Every original row/column, across all supplied tables, in stable projected order.
     * Only the explicitly added B23/C24 columns are excluded; old ID/JSON/clock fields stay.
     */
    public static Map<String,List<String>> originalColumnsSnapshot(JdbcTemplate owner,List<String> tables) {
        var snapshot=new TreeMap<String,List<String>>();
        for(String table:tables) {
            String added=switch(table) {
                case "requirement_revision","test_revision","defect_revision" -> "-'business_fields'";
                case "requirement_idempotency" -> "-'result_business_fields'";
                case "test_instance" -> "-'assignee_id'-'assignee_name'-'assignment_version'";
                case "execution_manifest" -> "-'assignee_id'-'assignee_name'";
                case "run_step" -> "-'id'-'executed_at'";
                case "attachment_link" -> "-'run_step_id'";
                default -> "";
            };
            snapshot.put(table,owner.query("SELECT (to_jsonb(t)"+added+")::text AS original FROM \""+table+"\" t ORDER BY original",(row,n)->row.getString(1)));
        }
        return snapshot;
    }

    public static void assertLegacyColumns(JdbcTemplate owner,Map<String,List<String>> before) {
        for(String table:List.of("test_instance","execution_manifest","run_step")) {
            String projection=switch(table) {
                case "test_instance" -> "-'assignee_id'-'assignee_name'-'assignment_version'";
                case "execution_manifest" -> "-'assignee_id'-'assignee_name'";
                default -> "-'id'-'executed_at'";
            };
            assertEquals(before.get(table),owner.query("SELECT (to_jsonb(t)"+projection+")::text AS original FROM "+table+" t ORDER BY original",(row,n)->row.getString(1)),"all original execution fields survive latest schema: "+table);
        }
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM run_step WHERE id IS NULL OR executed_at IS NOT NULL",Integer.class));
        assertEquals(0,owner.queryForObject("SELECT count(*) FROM execution_manifest WHERE assignee_id IS NOT NULL OR assignee_name IS NOT NULL",Integer.class));
    }

    public static void assertCurrentReadAndFrozenReplay(ExecutionService service,UUID actor,UUID project,
            UUID run,UUID attempt,String originalHash,String oldRunKey) {
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        var detail=service.run(actor,project,run);
        assertEquals(originalHash,detail.manifest().snapshotHash());
        assertEquals("m09-manifest-2",detail.manifest().formatVersion());
        assertNull(detail.manifest().assigneeId()); assertNull(detail.manifest().assigneeName());
        var current=service.attempt(actor,project,run,attempt);
        assertEquals("FAIL",current.conclusion());
        assertTrue(current.steps().stream().allMatch(step->step.runStepId()!=null && step.executedAt()==null));
        var replay=service.createRun(actor,project,detail.run().testInstanceId(),oldRunKey);
        assertEquals(run,replay.run().id()); assertEquals(originalHash,replay.manifest().snapshotHash());
        assertEquals("RUNNING",replay.currentAttempt().status(),"original create response remains frozen before later FAIL");
        assertTrue(replay.currentAttempt().steps().stream().allMatch(step->step.runStepId()==null && step.executedAt()==null && "NOT_RUN".equals(step.conclusion())));
        assertEquals(current,service.attempt(actor,project,run,attempt),"frozen replay cannot rewrite persisted history");
    }
}
