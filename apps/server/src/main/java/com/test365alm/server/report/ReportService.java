package com.test365alm.server.report;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

@Service
public class ReportService {
    private final JdbcTemplate jdbc;
    private final CoreCommands commands;
    public ReportService(JdbcTemplate jdbc,CoreCommands commands){this.jdbc=jdbc;this.commands=commands;}
    /** Fixed schema envelope; collections are complete or the whole request fails. */
    public record View(String kind,String version,OffsetDateTime generatedAt,String timeZone,UUID projectId,String projectName,
            Map<String,String> filters,JsonNode facts,List<JsonNode> items,List<JsonNode> steps,List<JsonNode> attachments,
            List<JsonNode> reviews,List<JsonNode> signatures,List<JsonNode> links,List<JsonNode> retests) { }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View run(UUID actor,UUID project,UUID run,UUID attempt) {
        var p=commands.scope(actor,project,false,false);
        JsonNode facts=one("""
            SELECT jsonb_build_object('run',jsonb_build_object('id',er.id,'test_instance_id',er.test_instance_id,'manifest_id',er.manifest_id,'created_at',er.created_at),'attempt',to_jsonb(a),'manifest',to_jsonb(m),
              'businessFields',tr.business_fields,'testSetName',ts.name,'testSetId',ts.id,
              'starterCurrentName',pr.display_name)::text
            FROM execution_run er JOIN run_attempt a ON a.run_id=er.id AND a.project_id=er.project_id
            JOIN execution_manifest m ON m.id=er.manifest_id AND m.project_id=er.project_id
            JOIN test_revision tr ON tr.id=m.source_revision_id AND tr.project_id=m.project_id
            JOIN test_instance i ON i.id=er.test_instance_id AND i.project_id=er.project_id
            JOIN test_set ts ON ts.id=i.test_set_id AND ts.project_id=i.project_id
            LEFT JOIN principal pr ON pr.id=a.started_by
            WHERE er.tenant_id=? AND er.project_id=? AND er.id=? AND a.id=?
            """,p.tenantId(),project,run,attempt);
        if(!facts.path("attempt").path("status").asText().equals("FINISHED"))
            throw ProjectAccessException.conflict("REPORT_ATTEMPT_NOT_FINISHED","Only FINISHED attempts have formal execution reports");
        var steps=rows("""
            SELECT (to_jsonb(s)||jsonb_build_object('recorderCurrentName',p.display_name,
              'updated_at',CASE WHEN s.updated_by IS NULL THEN NULL ELSE s.updated_at END))::text
            FROM run_step s LEFT JOIN principal p ON p.id=s.updated_by
            WHERE s.tenant_id=? AND s.project_id=? AND s.attempt_id=? ORDER BY s.ordinal LIMIT 5001
            """,5000,p.tenantId(),project,attempt);
        var attachments=rows("""
            SELECT jsonb_build_object('id',l.id,'run_step_id',l.run_step_id,'asset_id',l.asset_id,
              'asset_revision_id',l.asset_revision_id,'revision_no',r.revision_no,'filename',r.filename,
              'size',r.size,'sha256',r.sha256,'mime',r.mime,'created_at',l.created_at)::text
            FROM attachment_link l JOIN asset_revision r ON r.id=l.asset_revision_id AND r.project_id=l.project_id
            JOIN run_step s ON s.id=l.run_step_id AND s.project_id=l.project_id
            WHERE l.tenant_id=? AND l.project_id=? AND s.attempt_id=? ORDER BY s.ordinal,l.id LIMIT 5001
            """,5000,p.tenantId(),project,attempt);
        UUID test=UUID.fromString(facts.path("manifest").path("source_test_case_id").asText());
        UUID revision=UUID.fromString(facts.path("manifest").path("source_revision_id").asText());
        var reviews=rows("""
            SELECT (to_jsonb(e)||jsonb_build_object('actorCurrentName',a.display_name,'reviewerCurrentName',r.display_name))::text
            FROM test_review_event e LEFT JOIN principal a ON a.id=e.actor_id LEFT JOIN principal r ON r.id=e.reviewer_id
            WHERE e.tenant_id=? AND e.project_id=? AND e.test_case_id=? AND e.revision_id=?
            ORDER BY e.occurred_at,e.id LIMIT 5001
            """,5000,p.tenantId(),project,test,revision);
        var signed=rows("""
            SELECT payload::text FROM signature_record WHERE tenant_id=? AND project_id=? AND
              ((kind='RUN_COMPLETION' AND object_id=? AND snapshot_id=?) OR
               (kind='TEST_APPROVAL' AND object_id=? AND snapshot_id=?)) ORDER BY kind LIMIT 5001
            """,5000,p.tenantId(),project,run,attempt,test,revision);
        var defects=selectDefects(p,null,null,null,"",run,attempt);
        var related=relations(p,defects);
        return view("RUN",p,Map.of("runId",run.toString(),"attemptId",attempt.toString()),facts,defects,steps,
                attachments,reviews,signed,related.links(),related.retests());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View testSet(UUID actor,UUID project,UUID set,String requestedStatus) {
        var p=commands.scope(actor,project,false,false);
        String status=ReportRules.filter(requestedStatus,ReportRules.STATES);
        JsonNode info=one("SELECT to_jsonb(s)::text FROM test_set s WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,set);
        long total=jdbc.queryForObject("SELECT count(*) FROM test_instance WHERE tenant_id=? AND project_id=? AND test_set_id=?",Long.class,p.tenantId(),project,set);
        var items=rows("""
            SELECT jsonb_build_object('id',i.id,'test_case_id',i.test_case_id,'test_revision_id',i.test_revision_id,
              'revision_no',r.revision_no,'title',r.title,'assignee_id',i.assignee_id,'assignee_name',i.assignee_name,
              'run_id',latest.run_id,'attempt_id',latest.id,'attempt_no',latest.attempt_no,
              'status',COALESCE(latest.category,'NOT_RUN'),'conclusion',latest.conclusion,
              'started_at',latest.started_at,'finished_at',latest.finished_at,'otherActiveAttempts',
              (SELECT count(*) FROM execution_run er2 JOIN run_attempt a2 ON a2.run_id=er2.id AND a2.project_id=er2.project_id
               WHERE er2.project_id=i.project_id AND er2.test_instance_id=i.id AND a2.status<>'FINISHED' AND a2.id IS DISTINCT FROM latest.id))::text
            FROM test_instance i JOIN test_revision r ON r.id=i.test_revision_id AND r.project_id=i.project_id
            LEFT JOIN LATERAL (
              SELECT a.*,CASE WHEN a.status='FINISHED' THEN a.conclusion ELSE a.status END AS category
              FROM execution_run er JOIN run_attempt a ON a.run_id=er.id AND a.project_id=er.project_id
              WHERE er.tenant_id=i.tenant_id AND er.project_id=i.project_id AND er.test_instance_id=i.id
              ORDER BY a.started_at DESC,er.id DESC,a.id DESC LIMIT 1
            ) latest ON true
            WHERE i.tenant_id=? AND i.project_id=? AND i.test_set_id=?
              AND (?::text IS NULL OR COALESCE(latest.category,'NOT_RUN')=?)
            ORDER BY i.display_order,i.id LIMIT 1001
            """,1000,p.tenantId(),project,set,status,status);
        var counts=ReportRules.counts(items.stream().map(x->x.path("status").asText()).toList());
        long other=items.stream().mapToLong(x->x.path("otherActiveAttempts").asLong()).sum();
        var defects=selectDefects(p,set,null,null,"",null,null);
        var facts=CoreCommands.JSON.valueToTree(Map.of("testSet",info,"totalInstances",total,"counts",counts,
                "otherActiveAttempts",other,"relatedDefects",defects.size()));
        // Set deviations are linked using the same real source/retest relationship as the deviation report.
        return view("SET",p,filters(set,status,null,""),facts,items,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View defects(UUID actor,UUID project,UUID set,String requestedStatus,String requestedSeverity,String query) {
        var p=commands.scope(actor,project,false,false);
        if(set!=null)one("SELECT to_jsonb(s)::text FROM test_set s WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,set);
        String status=ReportRules.filter(requestedStatus,List.of("OPEN","IN_PROGRESS","RESOLVED","CLOSED","REOPENED"));
        String severity=ReportRules.filter(requestedSeverity,List.of("LOW","MEDIUM","HIGH","CRITICAL"));
        String q=query==null?"":query.strip();if(q.length()>200)throw ProjectAccessException.invalid("Report title filter is too long");
        var items=selectDefects(p,set,status,severity,q,null,null);
        var related=relations(p,items);
        return view("DEFECTS",p,filters(set,status,severity,q),CoreCommands.JSON.valueToTree(Map.of("matched",items.size())),
                items,List.of(),List.of(),List.of(),related.signatures(),related.links(),related.retests());
    }

    private List<JsonNode> selectDefects(ProjectService.ProjectView p,UUID set,String status,String severity,String q,UUID run,UUID attempt) {
        return rows("""
            SELECT jsonb_build_object('id',d.id,'display_number',d.display_number,'created_by',d.created_by,
              'created_at',d.created_at,'creatorCurrentName',pr.display_name,'resolved_revision_id',d.resolved_revision_id,
              'revision',to_jsonb(r))::text
            FROM defect d JOIN defect_revision r ON r.id=d.current_revision_id AND r.project_id=d.project_id
            LEFT JOIN principal pr ON pr.id=d.created_by
            WHERE d.tenant_id=? AND d.project_id=? AND (?::text IS NULL OR r.status=?)
              AND (?::text IS NULL OR r.severity=?) AND strpos(lower(r.title),lower(?))>0
              AND (?::uuid IS NULL OR EXISTS (
                SELECT 1 FROM defect_link l JOIN execution_run er ON er.id=l.run_id AND er.project_id=l.project_id
                JOIN test_instance i ON i.id=er.test_instance_id AND i.project_id=er.project_id
                WHERE l.project_id=d.project_id AND l.defect_id=d.id AND i.test_set_id=?
                UNION ALL
                SELECT 1 FROM defect_retest rt JOIN execution_run er ON er.id=rt.run_id AND er.project_id=rt.project_id
                JOIN test_instance i ON i.id=er.test_instance_id AND i.project_id=er.project_id
                WHERE rt.project_id=d.project_id AND rt.defect_id=d.id AND i.test_set_id=?))
              AND (?::uuid IS NULL OR EXISTS (
                SELECT 1 FROM defect_link l WHERE l.project_id=d.project_id AND l.defect_id=d.id AND l.run_id=? AND l.attempt_id=?
                UNION ALL SELECT 1 FROM defect_retest rt WHERE rt.project_id=d.project_id AND rt.defect_id=d.id AND rt.run_id=? AND rt.attempt_id=?))
            ORDER BY d.display_number,d.id LIMIT 501
            """,500,p.tenantId(),p.id(),status,status,severity,severity,q,set,set,set,run,run,attempt,run,attempt);
    }
    private record Relations(List<JsonNode> links,List<JsonNode> retests,List<JsonNode> signatures) { }
    private Relations relations(ProjectService.ProjectView p,List<JsonNode> defects) {
        if(defects.isEmpty())return new Relations(List.of(),List.of(),List.of());
        String slots=String.join(",",Collections.nCopies(defects.size(),"?"));
        List<Object> args=new ArrayList<>(List.of(p.tenantId(),p.id()));defects.forEach(d->args.add(UUID.fromString(d.path("id").asText())));
        var links=rows("SELECT to_jsonb(l)::text FROM defect_link l WHERE tenant_id=? AND project_id=? AND defect_id IN ("+slots+") ORDER BY defect_id,id LIMIT 5001",5000,args.toArray());
        var retests=rows("""
            SELECT (to_jsonb(rt)||jsonb_build_object('status',a.status,'conclusion',a.conclusion,'started_at',a.started_at,
              'finished_at',a.finished_at,'stepConclusion',s.conclusion,'stepActual',s.actual_result,'stepExecutedAt',s.executed_at))::text
            FROM defect_retest rt JOIN run_attempt a ON a.id=rt.attempt_id AND a.project_id=rt.project_id
            LEFT JOIN defect_link l ON l.defect_id=rt.defect_id AND l.project_id=rt.project_id AND l.kind='RUN_STEP'
            LEFT JOIN run_step s ON s.attempt_id=rt.attempt_id AND s.step_key=l.step_key AND s.project_id=rt.project_id
            WHERE rt.tenant_id=? AND rt.project_id=? AND rt.defect_id IN (
            """+slots+") ORDER BY rt.defect_id,rt.created_at,rt.id LIMIT 5001",5000,args.toArray());
        var signatures=rows("SELECT sr.payload::text FROM signature_record sr JOIN defect_revision r ON r.id=sr.snapshot_id AND r.project_id=sr.project_id AND r.defect_id=sr.object_id AND r.status='CLOSED' WHERE sr.tenant_id=? AND sr.project_id=? AND sr.kind='DEVIATION_CLOSURE' AND sr.object_id IN ("+slots+") ORDER BY sr.id LIMIT 5001",5000,args.toArray());
        return new Relations(links,retests,signatures);
    }
    private View view(String kind,ProjectService.ProjectView p,Map<String,String> filters,JsonNode facts,List<JsonNode> items,
            List<JsonNode> steps,List<JsonNode> attachments,List<JsonNode> reviews,List<JsonNode> signatures,List<JsonNode> links,List<JsonNode> retests) {
        var result=new View(kind,"F-REPORT-1",OffsetDateTime.now(ZoneOffset.UTC),"UTC",p.id(),p.name(),filters,facts,
                items,steps,attachments,reviews,signatures,links,retests);
        ReportRules.limit(CoreCommands.JSON.writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length,8*1024*1024);
        return result;
    }
    private List<JsonNode> rows(String sql,int max,Object... args) {
        var rows=jdbc.query(sql,(r,i)->CoreCommands.JSON.readTree(r.getString(1)),args);ReportRules.limit(rows.size(),max);return rows;
    }
    private JsonNode one(String sql,Object...args) {
        var values=rows(sql,1,args);if(values.isEmpty())throw ProjectAccessException.notFound();return values.get(0);
    }
    private Map<String,String> filters(UUID set,String status,String severity,String q) {
        return Map.of("testSetId",set==null?"":set.toString(),"status",status==null?"":status,"severity",severity==null?"":severity,"q",q);
    }
}
