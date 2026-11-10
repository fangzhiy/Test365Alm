package com.test365alm.server.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import tools.jackson.databind.JsonNode;

@Service
public class TestReviewService {
    private final JdbcTemplate jdbc;
    private final CoreCommands commands;
    public TestReviewService(JdbcTemplate jdbc, CoreCommands commands) { this.jdbc=jdbc; this.commands=commands; }

    @Transactional(readOnly=true)
    public ReviewView get(UUID actor, UUID project, UUID test, UUID revision, String cursor, Integer limit) {
        var p=commands.scope(actor,project,false,false);
        var content=content(p,test,revision,false);
        return view(p,actor,test,revision,content,cursor,limit);
    }

    @Transactional
    public ReviewView act(UUID actor, UUID project, UUID test, UUID revision, JsonNode body, String tag, String key) {
        commands.lock(project);
        var p=commands.scope(actor,project,true,false);
        long expected=CoreCommands.version(tag);
        var command=ReviewRules.command(body);
        var content=content(p,test,revision,true);
        // Even frozen success replays must reauthorize the exact designated identity.
        UUID reviewer=reviewer(content.fields());
        if(reviewer==null || !active(p,reviewer))
            throw ProjectAccessException.unprocessable("The revision needs an active designated project reviewer");
        if(!command.action().equals("SUBMIT") && !actor.equals(reviewer)) throw ProjectAccessException.forbidden();
        String route="test-review:"+test+":"+revision;
        String hash=CoreCommands.hash(test.toString(),revision.toString(),Long.toString(expected),command.action(),command.reason());
        ReviewView replay=commands.replay(p,actor,route,key,hash,ReviewView.class);
        if(replay!=null) return replay;
        if(!content.current().equals(revision))
            throw ProjectAccessException.conflict("REVIEW_REVISION_NOT_CURRENT","Only the current revision can be reviewed");
        if(content.version()!=expected) throw ProjectAccessException.preconditionFailed("STALE_VERSION","Test case was changed by another request");
        Event previous=latest(p,test,revision);
        String state=ReviewRules.next(previous==null?"DRAFT":previous.state(),command.action());
        long version=Math.addExact(expected,1);
        int changed=jdbc.update("UPDATE test_case SET row_version=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=? AND row_version=? AND current_revision_id=?",
            version,p.tenantId(),project,test,expected,revision);
        if(changed!=1) throw ProjectAccessException.preconditionFailed("STALE_VERSION","Test case was changed by another request");
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO test_review_event(id,tenant_id,project_id,test_case_id,revision_id,revision_no,row_version,
              action,state,reviewer_id,actor_id,content_digest,reason)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,p.tenantId(),project,test,revision,content.revisionNo(),version,command.action(),state,reviewer,actor,content.digest(),command.reason());
        Event event=latest(p,test,revision);
        String action="test_case.review."+state.toLowerCase(java.util.Locale.ROOT);
        jdbc.update("INSERT INTO audit_event(id,tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,?,'test_case',?,?,?)",
            id,p.tenantId(),project,actor,action,test,content.revisionNo(),content.digest());
        jdbc.update("INSERT INTO test_review_outbox_event(id,tenant_id,project_id,event_type,payload) VALUES(?,?,?,?,?::jsonb)",
            id,p.tenantId(),project,action,CoreCommands.JSON.writeValueAsString(event));
        var result=view(p,actor,test,revision,content(p,test,revision,false),null,null);
        commands.save(p,actor,route,key,hash,result);
        return result;
    }

    private ReviewView view(ProjectService.ProjectView p,UUID actor,UUID test,UUID revision,Content content,String cursor,Integer requestedLimit) {
        int n=CoreCommands.limit(requestedLimit);
        long before=Long.MAX_VALUE;
        if(cursor!=null) {
            try { if(!cursor.matches("[1-9][0-9]*"))throw new NumberFormatException(); before=Long.parseLong(cursor); }
            catch(NumberFormatException e){throw ProjectAccessException.invalid("Invalid review cursor");}
        }
        List<Event> events=jdbc.query("""
            SELECT * FROM test_review_event WHERE tenant_id=? AND project_id=? AND test_case_id=? AND revision_id=?
              AND row_version<? ORDER BY row_version DESC LIMIT ?
            """,TestReviewService::event,p.tenantId(),p.id(),test,revision,before,n+1);
        boolean more=events.size()>n;
        var items=more?events.subList(0,n):events;
        Event latest=latest(p,test,revision);
        UUID reviewer=reviewer(content.fields());
        String state=latest==null?"DRAFT":latest.state();
        boolean writer=active(p,actor), validReviewer=reviewer!=null && active(p,reviewer), current=content.current().equals(revision);
        return new ReviewView(test,revision,content.revisionNo(),content.current(),content.version(),content.digest(),reviewer,state,
            current&&writer&&validReviewer&&(state.equals("DRAFT")||state.equals("REJECTED")),
            current&&writer&&validReviewer&&actor.equals(reviewer)&&state.equals("SUBMITTED"),
            latest,List.copyOf(items),more?Long.toString(items.get(n-1).rowVersion()):null);
    }

    private Content content(ProjectService.ProjectView p,UUID test,UUID revision,boolean lock) {
        var rows=jdbc.query("""
            SELECT tc.current_revision_id,tc.row_version,tr.revision_no,tr.business_fields::text,
              app_test_review_digest(tc.tenant_id,tc.project_id,tc.id,tr.id) AS digest
            FROM test_case tc JOIN test_revision tr ON tr.tenant_id=tc.tenant_id AND tr.project_id=tc.project_id AND tr.test_case_id=tc.id
            WHERE tc.tenant_id=? AND tc.project_id=? AND tc.id=? AND tr.id=? AND tr.sealed_at IS NOT NULL
            """+(lock?" FOR UPDATE OF tc":""),
            (rs,i)->new Content(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3),CoreCommands.JSON.readTree(rs.getString(4)),rs.getString(5)),
            p.tenantId(),p.id(),test,revision);
        if(rows.isEmpty())throw ProjectAccessException.notFound();
        return rows.get(0);
    }
    private boolean active(ProjectService.ProjectView p,UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT app_has_active_project_writer(?,?,?)",Boolean.class,p.tenantId(),p.id(),actor));
    }
    private static UUID reviewer(JsonNode fields) {
        var field=fields.get("reviewer"); var value=field==null?null:field.get("value");
        if(value==null || !value.isTextual() || value.textValue().isBlank())return null;
        try{return UUID.fromString(value.textValue());}catch(IllegalArgumentException e){throw ProjectAccessException.unprocessable("Invalid stored reviewer identity");}
    }
    private Event latest(ProjectService.ProjectView p,UUID test,UUID revision) {
        var rows=jdbc.query("SELECT * FROM test_review_event WHERE tenant_id=? AND project_id=? AND test_case_id=? AND revision_id=? ORDER BY row_version DESC LIMIT 1",
            TestReviewService::event,p.tenantId(),p.id(),test,revision);
        return rows.isEmpty()?null:rows.get(0);
    }
    private static Event event(ResultSet r,int row) throws SQLException {
        return new Event(r.getObject("id",UUID.class),r.getObject("revision_id",UUID.class),r.getLong("revision_no"),r.getLong("row_version"),
            r.getString("action"),r.getString("state"),r.getObject("reviewer_id",UUID.class),r.getObject("actor_id",UUID.class),
            r.getString("content_digest"),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class));
    }
    private record Content(UUID current,long version,long revisionNo,JsonNode fields,String digest) { }
    public record Event(UUID id,UUID revisionId,long revisionNo,long rowVersion,String action,String state,UUID reviewerId,
        UUID actorId,String contentDigest,String reason,OffsetDateTime occurredAt) { }
    public record ReviewView(UUID testCaseId,UUID revisionId,long revisionNo,UUID currentRevisionId,long rowVersion,String contentDigest,
        UUID reviewerId,String state,boolean canSubmit,boolean canDecide,Event latest,List<Event> items,String nextCursor) { }
}
