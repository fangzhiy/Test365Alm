package com.test365alm.server.release;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/** A single SQL statement fixes the cross-module MVCC view for each evaluation. */
@Component
public class ReleaseMetrics {
    public static final String RULE="RELEASE_FROZEN_SCOPE_LATEST_FINISHED_NO_ACTIVE_V1";
    private final JdbcTemplate jdbc;
    public ReleaseMetrics(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public Facts evaluate(ProjectService.ProjectView project,UUID release) {
        JsonNode facts=CoreCommands.JSON.readTree(jdbc.queryForObject(SQL,String.class,project.tenantId(),project.id(),release));
        JsonNode requirements=facts.get("requirements"),tests=facts.get("tests"),defects=facts.get("defects");
        if(defects.size()>10000)throw ProjectAccessException.conflict("SNAPSHOT_EVIDENCE_LIMIT","More than 10000 related defects; evaluation requires a smaller scope");
        int covered=0,completed=0,passed=0,open=0;
        for(var requirement:requirements)if(!requirement.get("validTraceId").isNull())covered++;
        for(var test:tests) {
            JsonNode latest=test.get("latestFinished");
            boolean qualifies=test.get("activeCount").intValue()==0 && !latest.isNull()
                && test.get("testRevisionId").textValue().equals(latest.get("testRevisionId").textValue());
            if(qualifies) {completed++;if("PASS".equals(latest.get("conclusion").textValue()))passed++;}
        }
        for(var defect:defects)if(!"CLOSED".equals(defect.get("status").textValue()))open++;
        return new Facts(requirements.size(),covered,tests.size(),completed,passed,defects.size(),open,
            CoreCommands.hash(CoreCommands.canonical(facts.get("scope"))),facts);
    }
    public record Facts(int requirements,int covered,int planned,int completed,int passed,int defects,int openDefects,String fingerprint,JsonNode evidence) { }
    private static final String SQL="""
        WITH context AS (SELECT ?::uuid tenant,?::uuid project,?::uuid release),
        scope AS MATERIALIZED (
          SELECT s.* FROM release_scope s JOIN context c ON s.tenant_id=c.tenant AND s.project_id=c.project AND s.release_id=c.release
        ), requirements AS MATERIALIZED (
          SELECT DISTINCT requirement_id,requirement_revision_id FROM scope WHERE kind='REQUIREMENT'
        ), tests AS MATERIALIZED (
          SELECT DISTINCT test_case_id,test_revision_id FROM scope WHERE kind='TEST_CASE'
        ), req_facts AS (
          SELECT r.requirement_id,r.requirement_revision_id,valid.id valid_trace_id
          FROM requirements r CROSS JOIN context c
          LEFT JOIN LATERAL (
            SELECT l.id FROM trace_link l
            JOIN requirement q ON q.tenant_id=l.tenant_id AND q.project_id=l.project_id AND q.id=l.requirement_id AND q.current_revision_id=l.requirement_revision_id AND q.deleted_at IS NULL
            JOIN test_case t ON t.tenant_id=l.tenant_id AND t.project_id=l.project_id AND t.id=l.test_case_id AND t.current_revision_id=l.test_revision_id
            WHERE l.tenant_id=c.tenant AND l.project_id=c.project AND l.requirement_id=r.requirement_id AND l.requirement_revision_id=r.requirement_revision_id AND l.state='ACTIVE'
            ORDER BY l.id LIMIT 1
          ) valid ON true
        ), test_facts AS (
          SELECT t.test_case_id,t.test_revision_id,latest.fact latest_finished,
            (SELECT count(*) FROM run_attempt a JOIN execution_run er ON er.tenant_id=a.tenant_id AND er.project_id=a.project_id AND er.id=a.run_id
             JOIN execution_manifest m ON m.tenant_id=er.tenant_id AND m.project_id=er.project_id AND m.id=er.manifest_id
             WHERE a.tenant_id=c.tenant AND a.project_id=c.project AND m.source_test_case_id=t.test_case_id AND a.status IN('RUNNING','PAUSED')) active_count,
            (SELECT a.id FROM run_attempt a JOIN execution_run er ON er.tenant_id=a.tenant_id AND er.project_id=a.project_id AND er.id=a.run_id
             JOIN execution_manifest m ON m.tenant_id=er.tenant_id AND m.project_id=er.project_id AND m.id=er.manifest_id
             WHERE a.tenant_id=c.tenant AND a.project_id=c.project AND m.source_test_case_id=t.test_case_id AND a.status IN('RUNNING','PAUSED')
             ORDER BY a.started_at DESC,a.id DESC LIMIT 1) active_attempt_id
          FROM tests t CROSS JOIN context c
          LEFT JOIN LATERAL (
            SELECT jsonb_build_object('attemptId',a.id,'runId',a.run_id,'status',a.status,'conclusion',a.conclusion,
              'finishedAt',a.finished_at,'testRevisionId',m.source_revision_id,'manifestId',m.id,'rowVersion',a.row_version) fact
            FROM run_attempt a JOIN execution_run er ON er.tenant_id=a.tenant_id AND er.project_id=a.project_id AND er.id=a.run_id
            JOIN execution_manifest m ON m.tenant_id=er.tenant_id AND m.project_id=er.project_id AND m.id=er.manifest_id
            WHERE a.tenant_id=c.tenant AND a.project_id=c.project AND m.source_test_case_id=t.test_case_id AND a.status='FINISHED'
            ORDER BY a.finished_at DESC,a.id DESC LIMIT 1
          ) latest ON true
        ), related_defects AS (
          SELECT d.id,d.status,d.current_revision_id FROM defect d CROSS JOIN context c
          WHERE d.tenant_id=c.tenant AND d.project_id=c.project AND EXISTS(
            SELECT 1 FROM defect_link l WHERE l.tenant_id=d.tenant_id AND l.project_id=d.project_id AND l.defect_id=d.id
            AND ((l.kind='REQUIREMENT' AND EXISTS(SELECT 1 FROM requirements r WHERE r.requirement_id=l.requirement_id AND r.requirement_revision_id=l.requirement_revision_id))
             OR (l.kind='RUN_STEP' AND EXISTS(SELECT 1 FROM tests t WHERE t.test_case_id=l.test_case_id AND t.test_revision_id=l.test_revision_id)))
          ) ORDER BY d.id LIMIT 10001
        )
        SELECT jsonb_build_object(
          'scope',COALESCE((SELECT jsonb_agg(jsonb_build_object('scopeId',s.id,'cycleId',s.cycle_id,'kind',s.kind,'requirementId',s.requirement_id,'requirementRevisionId',s.requirement_revision_id,'testCaseId',s.test_case_id,'testRevisionId',s.test_revision_id) ORDER BY s.id) FROM scope s),'[]'::jsonb),
          'requirements',COALESCE((SELECT jsonb_agg(jsonb_build_object('requirementId',r.requirement_id,'requirementRevisionId',r.requirement_revision_id,'validTraceId',r.valid_trace_id) ORDER BY r.requirement_id) FROM req_facts r),'[]'::jsonb),
          'tests',COALESCE((SELECT jsonb_agg(jsonb_build_object('testCaseId',t.test_case_id,'testRevisionId',t.test_revision_id,'latestFinished',t.latest_finished,'activeCount',t.active_count,'activeAttemptId',t.active_attempt_id) ORDER BY t.test_case_id) FROM test_facts t),'[]'::jsonb),
          'defects',COALESCE((SELECT jsonb_agg(jsonb_build_object('defectId',d.id,'revisionId',d.current_revision_id,'status',d.status) ORDER BY d.id) FROM related_defects d),'[]'::jsonb)
        )::text
        """;
}
