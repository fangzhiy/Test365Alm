package com.test365alm.server.trace;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.testcase.TestCaseService;

/** Typed, same-project requirement -> manual test identity links. */
@Service
public class TraceLinkService {
    private final JdbcTemplate jdbc;private final CoreCommands commands;private final RequirementService requirements;private final TestCaseService tests;
    public TraceLinkService(JdbcTemplate jdbc,CoreCommands commands,RequirementService requirements,TestCaseService tests){this.jdbc=jdbc;this.commands=commands;this.requirements=requirements;this.tests=tests;}
    @Transactional public LinkView create(UUID actor,UUID project,UUID requirement,UUID test,String key){
        commands.lock(project);var p=commands.scope(actor,project,true,false);
        if(requirement==null || test==null)throw ProjectAccessException.invalid("Both typed endpoints are required");
        String hash=CoreCommands.hash(requirement.toString(),test.toString());var replay=commands.replay(p,actor,"trace:create",key,hash,LinkView.class);if(replay!=null)return replay;
        var req=requirements.getAuthorized(actor,project,requirement);var tc=tests.getAuthorized(actor,project,test);
        commands.scope(actor,project,true,false);
        if(jdbc.queryForObject("SELECT count(*) FROM test_revision WHERE tenant_id=? AND project_id=? AND test_case_id=? AND id=? AND sealed_at IS NOT NULL",Integer.class,p.tenantId(),project,test,tc.currentRevision().id())!=1)throw ProjectAccessException.invalid("Test revision is not sealed");
        if(jdbc.queryForObject("SELECT count(*) FROM trace_link WHERE tenant_id=? AND project_id=? AND requirement_id=? AND test_case_id=? AND state='ACTIVE'",Integer.class,p.tenantId(),project,requirement,test)>0)throw ProjectAccessException.conflict("DUPLICATE_LINK","An active link already exists");
        UUID id=UUID.randomUUID();var v=new LinkView(id,project,requirement,test,req.currentRevisionId(),tc.currentRevision().id(),1,"ACTIVE");
        jdbc.update("INSERT INTO trace_link(tenant_id,project_id,id,requirement_id,test_case_id,requirement_revision_id,test_revision_id,state,row_version,created_by) VALUES(?,?,?,?,?,?,?,'ACTIVE',1,?)",p.tenantId(),project,id,requirement,test,req.currentRevisionId(),tc.currentRevision().id(),actor);
        jdbc.update("INSERT INTO trace_link_revision(tenant_id,project_id,link_id,revision_no,state,created_by) VALUES(?,?,?,1,'ACTIVE',?)",p.tenantId(),project,id,actor);
        commands.event(p,actor,"trace_link",id,1,"trace.created",v);commands.save(p,actor,"trace:create",key,hash,v);return v;
    }
    @Transactional public LinkView invalidate(UUID actor,UUID project,UUID id,String tag,String key){
        commands.lock(project);var p=commands.scope(actor,project,true,false);long version=CoreCommands.version(tag);
        String route="trace:invalidate:"+id,hash=CoreCommands.hash(Long.toString(version));var replay=commands.replay(p,actor,route,key,hash,LinkView.class);if(replay!=null)return replay;
        var rows=jdbc.query("SELECT * FROM trace_link WHERE tenant_id=? AND project_id=? AND id=?",TraceLinkService::map,p.tenantId(),project,id);
        if(rows.isEmpty())throw ProjectAccessException.notFound();var old=rows.get(0);
        if(old.rowVersion()!=version)throw ProjectAccessException.preconditionFailed("STALE_VERSION","Link changed");
        if(!old.state().equals("ACTIVE"))throw ProjectAccessException.conflict("LINK_INACTIVE","Link already inactive");
        jdbc.update("INSERT INTO trace_link_revision(tenant_id,project_id,link_id,revision_no,state,created_by) VALUES(?,?,?,?,'INACTIVE',?)",p.tenantId(),project,id,version+1,actor);
        jdbc.update("UPDATE trace_link SET state='INACTIVE',row_version=row_version+1 WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,id);
        var v=new LinkView(id,project,old.requirementId(),old.testCaseId(),old.requirementRevisionId(),old.testRevisionId(),version+1,"INACTIVE");
        commands.event(p,actor,"trace_link",id,version+1,"trace.invalidated",v);commands.save(p,actor,route,key,hash,v);return v;
    }
    @Transactional(readOnly=true) public CoreCommands.Page<LinkView> list(UUID actor,UUID project,UUID requirement,String cursor,Integer limit){
        requirements.getAuthorized(actor,project,requirement);var p=commands.scope(actor,project,false,false);int n=CoreCommands.limit(limit);
        var rows=jdbc.query("SELECT * FROM trace_link WHERE tenant_id=? AND project_id=? AND requirement_id=? AND id>? ORDER BY id LIMIT ?",TraceLinkService::map,p.tenantId(),project,requirement,CoreCommands.cursor(cursor),n+1);
        boolean more=rows.size()>n;var items=more?rows.subList(0,n):rows;return new CoreCommands.Page<>(items,more?items.get(n-1).id().toString():null);
    }
    @Transactional(readOnly=true) public Coverage coverage(UUID actor,UUID project,UUID requirement){
        var req=requirements.getAuthorized(actor,project,requirement);var p=commands.scope(actor,project,false,false);
        // One authorized DB statement; no all-project download or asynchronous projection claim.
        return jdbc.queryForObject("""
            WITH linked AS (
              SELECT l.*,t.current_revision_id FROM trace_link l JOIN test_case t
               ON t.tenant_id=l.tenant_id AND t.project_id=l.project_id AND t.id=l.test_case_id
              WHERE l.tenant_id=? AND l.project_id=? AND l.requirement_id=? AND l.state='ACTIVE'
            ), facts AS (
              SELECT l.*,last_finished.conclusion,last_finished.source_revision_id,
               EXISTS(SELECT 1 FROM run_attempt a JOIN execution_run r ON r.tenant_id=a.tenant_id AND r.project_id=a.project_id AND r.id=a.run_id
                 JOIN execution_manifest m ON m.tenant_id=r.tenant_id AND m.project_id=r.project_id AND m.id=r.manifest_id
                 WHERE a.tenant_id=l.tenant_id AND a.project_id=l.project_id AND m.source_test_case_id=l.test_case_id AND a.status<>'FINISHED') AS active
              FROM linked l LEFT JOIN LATERAL (
                SELECT a.conclusion,m.source_revision_id FROM run_attempt a
                JOIN execution_run r ON r.tenant_id=a.tenant_id AND r.project_id=a.project_id AND r.id=a.run_id
                JOIN execution_manifest m ON m.tenant_id=r.tenant_id AND m.project_id=r.project_id AND m.id=r.manifest_id
                WHERE a.tenant_id=l.tenant_id AND a.project_id=l.project_id AND m.source_test_case_id=l.test_case_id AND a.status='FINISHED'
                ORDER BY a.finished_at DESC,a.id DESC LIMIT 1
              ) last_finished ON TRUE
            ) SELECT count(*),count(*) FILTER(WHERE active),count(*) FILTER(WHERE NOT active AND conclusion='PASS'
                AND requirement_revision_id=? AND test_revision_id=current_revision_id AND source_revision_id=test_revision_id) FROM facts
            """,(rs,i)->new Coverage(requirement,rs.getLong(1),rs.getLong(2),rs.getLong(3),"CURRENT_BOUND_REVISIONS_LATEST_FINISHED_NO_ACTIVE"),p.tenantId(),project,requirement,req.currentRevisionId());
    }
    private static LinkView map(java.sql.ResultSet r,int i)throws java.sql.SQLException{return new LinkView(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("requirement_id",UUID.class),r.getObject("test_case_id",UUID.class),r.getObject("requirement_revision_id",UUID.class),r.getObject("test_revision_id",UUID.class),r.getLong("row_version"),r.getString("state"));}
    public record LinkView(UUID id,UUID projectId,UUID requirementId,UUID testCaseId,UUID requirementRevisionId,UUID testRevisionId,long rowVersion,String state){}
    public record Coverage(UUID requirementId,long linkedTests,long activeTests,long passedTests,String rule){}
}
