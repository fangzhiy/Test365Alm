package com.test365alm.server.release;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.*;

/** M06 commands are authorized and committed on the restricted runtime datasource. */
@Service
public class ReleaseService {
    private final JdbcTemplate jdbc;
    private final CoreCommands commands;
    private final ReleaseMetrics metrics;
    public ReleaseService(JdbcTemplate jdbc,CoreCommands commands,ReleaseMetrics metrics) {
        this.jdbc=jdbc;this.commands=commands;this.metrics=metrics;
    }
    private ProjectService.ProjectView authorize(UUID actor,UUID project,boolean write) {
        return commands.scope(actor,project,write,write);
    }
    private ProjectService.ProjectView commandScope(UUID actor,UUID project) {
        commands.lock(project);
        return authorize(actor,project,true);
    }
    @Transactional public ReleaseView create(UUID actor,UUID project,JsonNode body,String key) {
        var p=commandScope(actor,project);var content=ReleaseRules.release(body,null);
        String route="release:create",hash=CoreCommands.hash(CoreCommands.canonical(body));
        var replay=commands.replay(p,actor,route,key,hash,ReleaseView.class);if(replay!=null)return replay;
        jdbc.update("INSERT INTO release_number_allocator(tenant_id,project_id) VALUES(?,?) ON CONFLICT DO NOTHING",p.tenantId(),project);
        long number=jdbc.queryForObject("UPDATE release_number_allocator SET next_number=next_number+1 WHERE tenant_id=? AND project_id=? RETURNING next_number-1",Long.class,p.tenantId(),project);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO release_plan(tenant_id,project_id,id,display_number,name,description,start_date,end_date,status,row_version,created_by) VALUES(?,?,?,?,?,?,?,?,'PLANNED',1,?)",p.tenantId(),project,id,number,content.name(),content.description(),content.startDate(),content.endDate(),actor);
        var result=read(p,id);event(p,actor,id,null,null,1,"RELEASE_CREATED",Map.of("releaseId",id,"version",1));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional public ReleaseView edit(UUID actor,UUID project,UUID id,JsonNode body,String tag,String key) {
        var p=commandScope(actor,project);long version=CoreCommands.version(tag);
        String route="release:edit:"+id,hash=CoreCommands.hash(Long.toString(version),CoreCommands.canonical(body));
        var replay=commands.replay(p,actor,route,key,hash,ReleaseView.class);if(replay!=null)return replay;
        var old=read(p,id);version(old.rowVersion(),version);var c=ReleaseRules.release(body,content(old));
        if(jdbc.queryForObject("SELECT count(*) FROM release_cycle WHERE tenant_id=? AND project_id=? AND release_id=? AND(start_date<? OR end_date>?)",Integer.class,p.tenantId(),project,id,c.startDate(),c.endDate())>0)throw outside();
        jdbc.update("UPDATE release_plan SET name=?,description=?,start_date=?,end_date=?,status=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",c.name(),c.description(),c.startDate(),c.endDate(),c.status(),p.tenantId(),project,id);
        var result=read(p,id);event(p,actor,id,null,null,result.rowVersion(),c.status().equals("ARCHIVED")?"RELEASE_ARCHIVED":"RELEASE_UPDATED",Map.of("releaseId",id,"version",result.rowVersion(),"status",result.status()));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional(readOnly=true) public ReleaseView get(UUID actor,UUID project,UUID id) {return read(authorize(actor,project,false),id);}
    @Transactional(readOnly=true) public CoreCommands.Page<ReleaseView> list(UUID actor,UUID project,String cursor,Integer limit) {
        var p=authorize(actor,project,false);int n=CoreCommands.limit(limit);
        return page(jdbc.query("SELECT * FROM release_plan WHERE tenant_id=? AND project_id=? AND id>? ORDER BY id LIMIT ?",ReleaseService::release,p.tenantId(),project,CoreCommands.cursor(cursor),n+1),n,ReleaseView::id);
    }
    @Transactional public CycleView createCycle(UUID actor,UUID project,UUID release,JsonNode body,String key) {
        var p=commandScope(actor,project);var c=ReleaseRules.cycle(body,null);
        String route="release:cycle:create:"+release,hash=CoreCommands.hash(CoreCommands.canonical(body));
        var replay=commands.replay(p,actor,route,key,hash,CycleView.class);if(replay!=null)return replay;
        var parent=read(p,release);mutable(parent);within(parent,c);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO release_cycle(tenant_id,project_id,release_id,id,name,start_date,end_date,status,row_version,created_by) VALUES(?,?,?,?,?,?,?,'PLANNED',1,?)",p.tenantId(),project,release,id,c.name(),c.startDate(),c.endDate(),actor);
        var result=readCycle(p,release,id);event(p,actor,release,id,null,1,"CYCLE_CREATED",Map.of("cycleId",id,"version",1));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional public CycleView editCycle(UUID actor,UUID project,UUID release,UUID id,JsonNode body,String tag,String key) {
        var p=commandScope(actor,project);long version=CoreCommands.version(tag);
        String route="release:cycle:edit:"+release+":"+id,hash=CoreCommands.hash(Long.toString(version),CoreCommands.canonical(body));
        var replay=commands.replay(p,actor,route,key,hash,CycleView.class);if(replay!=null)return replay;
        var parent=read(p,release);mutable(parent);var old=readCycle(p,release,id);version(old.rowVersion(),version);
        var c=ReleaseRules.cycle(body,new ReleaseRules.Content(old.name(),"",old.startDate(),old.endDate(),old.status()));within(parent,c);
        jdbc.update("UPDATE release_cycle SET name=?,start_date=?,end_date=?,status=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND release_id=? AND id=?",c.name(),c.startDate(),c.endDate(),c.status(),p.tenantId(),project,release,id);
        var result=readCycle(p,release,id);event(p,actor,release,id,null,result.rowVersion(),"CYCLE_UPDATED",Map.of("cycleId",id,"version",result.rowVersion(),"status",result.status()));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional(readOnly=true) public CycleView getCycle(UUID actor,UUID project,UUID release,UUID id) {
        var p=authorize(actor,project,false);read(p,release);return readCycle(p,release,id);
    }
    @Transactional(readOnly=true) public CoreCommands.Page<CycleView> cycles(UUID actor,UUID project,UUID release,String cursor,Integer limit) {
        var p=authorize(actor,project,false);read(p,release);int n=CoreCommands.limit(limit);
        return page(jdbc.query("SELECT * FROM release_cycle WHERE tenant_id=? AND project_id=? AND release_id=? AND id>? ORDER BY id LIMIT ?",ReleaseService::cycle,p.tenantId(),project,release,CoreCommands.cursor(cursor),n+1),n,CycleView::id);
    }
    @Transactional public ScopeView addScope(UUID actor,UUID project,UUID release,JsonNode body,String key) {
        var p=commandScope(actor,project);FieldRules.fields(body,Set.of("kind","requirementId","testCaseId","cycleId"));
        String kind=FieldRules.text(body.get("kind"));
        if(!Set.of("REQUIREMENT","TEST_CASE").contains(kind))throw ProjectAccessException.invalid("Scope kind must be REQUIREMENT or TEST_CASE");
        if(body.has(kind.equals("REQUIREMENT")?"testCaseId":"requirementId"))throw ProjectAccessException.invalid("Scope must contain exactly the typed reference");
        UUID endpoint=uuid(body.get(kind.equals("REQUIREMENT")?"requirementId":"testCaseId"));
        UUID cycle=body.has("cycleId")?uuid(body.get("cycleId")):null;
        String route="release:scope:add:"+release,hash=CoreCommands.hash(CoreCommands.canonical(body));
        var replay=commands.replay(p,actor,route,key,hash,ScopeView.class);if(replay!=null)return replay;
        var parent=read(p,release);mutable(parent);if(cycle!=null)mutable(readCycle(p,release,cycle));
        if(jdbc.queryForObject("SELECT count(*) FROM release_scope WHERE tenant_id=? AND project_id=? AND release_id=?",Integer.class,p.tenantId(),project,release)>=1000)throw ProjectAccessException.conflict("SCOPE_LIMIT","Release scope is limited to 1000 memberships");
        UUID revision;
        if(kind.equals("REQUIREMENT"))revision=referenceRevision("SELECT current_revision_id FROM requirement WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL",p,endpoint);
        else revision=referenceRevision("SELECT t.current_revision_id FROM test_case t JOIN test_revision r ON r.id=t.current_revision_id WHERE t.tenant_id=? AND t.project_id=? AND t.id=? AND r.sealed_at IS NOT NULL",p,endpoint);
        String idColumn=kind.equals("REQUIREMENT")?"requirement_id":"test_case_id",revisionColumn=kind.equals("REQUIREMENT")?"requirement_revision_id":"test_revision_id";
        var existing=jdbc.query("SELECT "+revisionColumn+",cycle_id FROM release_scope WHERE tenant_id=? AND project_id=? AND release_id=? AND "+idColumn+"=?",(r,i)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},p.tenantId(),project,release,endpoint);
        for(var row:existing) {
            if(!row[0].equals(revision))throw ProjectAccessException.conflict("SCOPE_REVISION_CONFLICT","Remove the existing frozen memberships before adding a different revision");
            if(Objects.equals(row[1],cycle))throw ProjectAccessException.conflict("DUPLICATE_SCOPE","Object is already in this release/cycle scope");
        }
        UUID id=UUID.randomUUID(),requirement=kind.equals("REQUIREMENT")?endpoint:null,test=kind.equals("TEST_CASE")?endpoint:null;
        jdbc.update("INSERT INTO release_scope(tenant_id,project_id,release_id,id,cycle_id,kind,requirement_id,requirement_revision_id,test_case_id,test_revision_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)",p.tenantId(),project,release,id,cycle,kind,requirement,requirement==null?null:revision,test,test==null?null:revision,actor);
        var result=readScope(p,release,id);
        event(p,actor,release,cycle,null,parent.rowVersion(),"RELEASE_SCOPE_ADDED",Map.of("scopeId",id,"kind",kind,"sourceId",endpoint,"revisionId",revision,"scopeVersion",parent.scopeVersion()+1));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional public Removed removeScope(UUID actor,UUID project,UUID release,UUID id,String key) {
        var p=commandScope(actor,project);String route="release:scope:remove:"+release+":"+id,hash=CoreCommands.hash(id.toString());
        var replay=commands.replay(p,actor,route,key,hash,Removed.class);if(replay!=null)return replay;
        var parent=read(p,release);mutable(parent);var old=readScope(p,release,id);if(old.cycleId()!=null)mutable(readCycle(p,release,old.cycleId()));
        jdbc.update("DELETE FROM release_scope WHERE tenant_id=? AND project_id=? AND release_id=? AND id=?",p.tenantId(),project,release,id);
        var result=new Removed(id,true);event(p,actor,release,old.cycleId(),null,parent.rowVersion(),"RELEASE_SCOPE_REMOVED",Map.of("scopeId",id,"scopeVersion",parent.scopeVersion()+1));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional(readOnly=true) public CoreCommands.Page<ScopeView> scope(UUID actor,UUID project,UUID release,String cursor,Integer limit) {
        var p=authorize(actor,project,false);read(p,release);int n=CoreCommands.limit(limit);
        return page(jdbc.query("SELECT * FROM release_scope WHERE tenant_id=? AND project_id=? AND release_id=? AND id>? ORDER BY id LIMIT ?",ReleaseService::scopeRow,p.tenantId(),project,release,CoreCommands.cursor(cursor),n+1),n,ScopeView::id);
    }
    @Transactional public Snapshot evaluate(UUID actor,UUID project,UUID release,String key) {
        var p=commandScope(actor,project);String route="release:evaluate:"+release,hash=CoreCommands.hash("{}");
        var replay=commands.replay(p,actor,route,key,hash,Snapshot.class);if(replay!=null)return replay;
        var parent=one(jdbc.query("SELECT * FROM release_plan WHERE tenant_id=? AND project_id=? AND id=? FOR UPDATE",ReleaseService::release,p.tenantId(),project,release));
        if(parent.status().equals("ARCHIVED"))throw ProjectAccessException.conflict("RELEASE_READ_ONLY","Archived release cannot be evaluated");
        // One SQL statement observes all metric sources at the same MVCC snapshot,
        // including execution transactions that use their own per-run locks.
        var facts=metrics.evaluate(p,release);UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO quality_snapshot(tenant_id,project_id,release_id,id,evaluated_by,metric_version,rule,scope_version,scope_fingerprint,requirement_count,coverage_count,planned_count,completed_count,pass_count,related_defect_count,open_defect_count,evidence) VALUES(?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?::jsonb)",p.tenantId(),project,release,id,actor,ReleaseMetrics.RULE,parent.scopeVersion(),facts.fingerprint(),facts.requirements(),facts.covered(),facts.planned(),facts.completed(),facts.passed(),facts.defects(),facts.openDefects(),CoreCommands.JSON.writeValueAsString(facts.evidence()));
        var result=readSnapshot(p,release,id);event(p,actor,release,null,id,parent.rowVersion(),"QUALITY_SNAPSHOT_CREATED",Map.of("snapshotId",id,"metricVersion",1,"scopeVersion",parent.scopeVersion(),"requirements",facts.requirements(),"plannedTests",facts.planned()));
        commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional(readOnly=true) public Snapshot snapshot(UUID actor,UUID project,UUID release,UUID id) {
        var p=authorize(actor,project,false);read(p,release);return readSnapshot(p,release,id);
    }
    @Transactional(readOnly=true) public CoreCommands.Page<SnapshotSummary> snapshots(UUID actor,UUID project,UUID release,String cursor,Integer limit) {
        var p=authorize(actor,project,false);read(p,release);int n=CoreCommands.limit(limit);
        // Summary query intentionally excludes the immutable evidence document.
        return page(jdbc.query("SELECT id,project_id,release_id,evaluated_at,evaluated_by,metric_version,rule,scope_version,scope_fingerprint,requirement_count,coverage_count,planned_count,completed_count,pass_count,related_defect_count,open_defect_count FROM quality_snapshot WHERE tenant_id=? AND project_id=? AND release_id=? AND id>? ORDER BY id LIMIT ?",ReleaseService::summary,p.tenantId(),project,release,CoreCommands.cursor(cursor),n+1),n,SnapshotSummary::id);
    }
    private ReleaseView read(ProjectService.ProjectView p,UUID id) {return one(jdbc.query("SELECT * FROM release_plan WHERE tenant_id=? AND project_id=? AND id=?",ReleaseService::release,p.tenantId(),p.id(),id));}
    private CycleView readCycle(ProjectService.ProjectView p,UUID release,UUID id) {return one(jdbc.query("SELECT * FROM release_cycle WHERE tenant_id=? AND project_id=? AND release_id=? AND id=?",ReleaseService::cycle,p.tenantId(),p.id(),release,id));}
    private ScopeView readScope(ProjectService.ProjectView p,UUID release,UUID id) {return one(jdbc.query("SELECT * FROM release_scope WHERE tenant_id=? AND project_id=? AND release_id=? AND id=?",ReleaseService::scopeRow,p.tenantId(),p.id(),release,id));}
    private Snapshot readSnapshot(ProjectService.ProjectView p,UUID release,UUID id) {
        return one(jdbc.query("SELECT * FROM quality_snapshot WHERE tenant_id=? AND project_id=? AND release_id=? AND id=?",(r,i)->{
            var s=summary(r,i);return new Snapshot(s.id(),s.projectId(),s.releaseId(),s.evaluatedAt(),s.evaluatedBy(),s.metricVersion(),s.rule(),s.scopeVersion(),s.scopeFingerprint(),s.requirementCount(),s.coverage(),s.plannedTests(),s.completed(),s.effectivePass(),s.openDefects(),CoreCommands.JSON.readTree(r.getString("evidence")));
        },p.tenantId(),p.id(),release,id));
    }
    private UUID referenceRevision(String sql,ProjectService.ProjectView p,UUID id) {return one(jdbc.query(sql,(r,i)->r.getObject(1,UUID.class),p.tenantId(),p.id(),id));}
    private void event(ProjectService.ProjectView p,UUID actor,UUID release,UUID cycle,UUID snapshot,long version,String action,Object payload) {
        String json=CoreCommands.JSON.writeValueAsString(payload);
        jdbc.update("INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,'release_plan',?,?,?)",p.tenantId(),p.id(),actor,action,release,version,CoreCommands.hash(json));
        jdbc.update("INSERT INTO release_outbox_event(tenant_id,project_id,release_id,cycle_id,snapshot_id,object_version,event_type,payload) VALUES(?,?,?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),release,cycle,snapshot,version,action,json);
    }
    private static void version(long actual,long expected) {if(actual!=expected)throw ProjectAccessException.preconditionFailed("STALE_VERSION","Object changed; compare latest with the saved draft");}
    private static void mutable(ReleaseView r) {if(!Set.of("PLANNED","ACTIVE").contains(r.status()))throw ProjectAccessException.conflict("RELEASE_READ_ONLY","Closed or archived release cannot be changed");}
    private static void mutable(CycleView c) {if(c.status().equals("CLOSED"))throw ProjectAccessException.conflict("CYCLE_READ_ONLY","Closed cycle cannot be changed");}
    private static void within(ReleaseView r,ReleaseRules.Content c) {if(c.startDate().isBefore(r.startDate()) || c.endDate().isAfter(r.endDate()))throw outside();}
    private static ProjectAccessException outside() {return new ProjectAccessException("CYCLE_OUTSIDE_RELEASE",HttpStatus.UNPROCESSABLE_ENTITY,"Cycle dates must lie within the release");}
    private static ReleaseRules.Content content(ReleaseView r) {return new ReleaseRules.Content(r.name(),r.description(),r.startDate(),r.endDate(),r.status());}
    private static UUID uuid(JsonNode n) {try{return UUID.fromString(FieldRules.text(n));}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("A UUID reference is required");}}
    private static <T> T one(List<T> rows) {if(rows.isEmpty())throw ProjectAccessException.notFound();return rows.get(0);}
    private static <T> CoreCommands.Page<T> page(List<T> rows,int n,java.util.function.Function<T,UUID> id) {boolean more=rows.size()>n;var items=List.copyOf(more?rows.subList(0,n):rows);return new CoreCommands.Page<>(items,more?id.apply(items.get(n-1)).toString():null);}
    private static ReleaseView release(ResultSet r,int i)throws SQLException {return new ReleaseView(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getLong("display_number"),r.getString("name"),r.getString("description"),r.getObject("start_date",LocalDate.class),r.getObject("end_date",LocalDate.class),r.getString("status"),r.getLong("row_version"),r.getLong("scope_version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class));}
    private static CycleView cycle(ResultSet r,int i)throws SQLException {return new CycleView(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("release_id",UUID.class),r.getString("name"),r.getObject("start_date",LocalDate.class),r.getObject("end_date",LocalDate.class),r.getString("status"),r.getLong("row_version"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class));}
    private static ScopeView scopeRow(ResultSet r,int i)throws SQLException {return new ScopeView(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("release_id",UUID.class),r.getObject("cycle_id",UUID.class),r.getString("kind"),r.getObject("requirement_id",UUID.class),r.getObject("requirement_revision_id",UUID.class),r.getObject("test_case_id",UUID.class),r.getObject("test_revision_id",UUID.class),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class));}
    private static SnapshotSummary summary(ResultSet r,int i)throws SQLException {int req=r.getInt("requirement_count"),planned=r.getInt("planned_count");return new SnapshotSummary(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("release_id",UUID.class),r.getObject("evaluated_at",OffsetDateTime.class),r.getObject("evaluated_by",UUID.class),r.getInt("metric_version"),r.getString("rule"),r.getLong("scope_version"),r.getString("scope_fingerprint"),req,new Metric(r.getInt("coverage_count"),req),new Metric(planned,planned),new Metric(r.getInt("completed_count"),planned),new Metric(r.getInt("pass_count"),planned),new Metric(r.getInt("open_defect_count"),r.getInt("related_defect_count")));}
    public record ReleaseView(UUID id,UUID projectId,long displayNumber,String name,String description,LocalDate startDate,LocalDate endDate,String status,long rowVersion,long scopeVersion,UUID createdBy,OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record CycleView(UUID id,UUID projectId,UUID releaseId,String name,LocalDate startDate,LocalDate endDate,String status,long rowVersion,UUID createdBy,OffsetDateTime createdAt,OffsetDateTime updatedAt) { }
    public record ScopeView(UUID id,UUID projectId,UUID releaseId,UUID cycleId,String kind,UUID requirementId,UUID requirementRevisionId,UUID testCaseId,UUID testRevisionId,UUID createdBy,OffsetDateTime createdAt) { }
    public record Removed(UUID id,boolean removed) { }
    public record Metric(int numerator,int denominator) { }
    public record SnapshotSummary(UUID id,UUID projectId,UUID releaseId,OffsetDateTime evaluatedAt,UUID evaluatedBy,int metricVersion,String rule,long scopeVersion,String scopeFingerprint,int requirementCount,Metric coverage,Metric plannedTests,Metric completed,Metric effectivePass,Metric openDefects) { }
    public record Snapshot(UUID id,UUID projectId,UUID releaseId,OffsetDateTime evaluatedAt,UUID evaluatedBy,int metricVersion,String rule,long scopeVersion,String scopeFingerprint,int requirementCount,Metric coverage,Metric plannedTests,Metric completed,Metric effectivePass,Metric openDefects,JsonNode evidence) { }
}
