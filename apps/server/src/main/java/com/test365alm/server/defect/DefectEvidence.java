package com.test365alm.server.defect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.project.*;

/** Typed source and retest evidence. Called inside DefectService transactions. */
@Component
public class DefectEvidence {
 private final JdbcTemplate jdbc;private final ExecutionService execution;private final RequirementService requirements;
 public DefectEvidence(JdbcTemplate jdbc,ExecutionService execution,RequirementService requirements){this.jdbc=jdbc;this.execution=execution;this.requirements=requirements;}
 public record SourceRef(UUID runId,UUID attemptId,UUID stepKey){}
 public record Link(UUID id,String kind,UUID requirementId,UUID requirementRevisionId,UUID testCaseId,UUID testRevisionId,
  UUID runId,UUID attemptId,UUID manifestId,UUID stepKey,String actualResult,String expectedResult,String sourceConclusion,Long sourceVersion){}
 public record Verification(boolean eligible,UUID runId,UUID attemptId,String reason){}
 public record RetestStart(UUID runId,UUID attemptId){}
 public record Retest(UUID id,UUID runId,UUID attemptId,UUID resolvedRevisionId,int attemptNo,String status,String conclusion){}
 public List<Link> prepare(UUID actor,ProjectService.ProjectView p,UUID requirement,SourceRef ref){
  List<Link> result=new ArrayList<>();
  if(requirement!=null){var r=requirements.getAuthorized(actor,p.id(),requirement);result.add(requirementLink(r.id(),r.currentRevisionId()));}
  if(ref!=null){
   var a=execution.attempt(actor,p.id(),ref.runId(),ref.attemptId());var run=execution.run(actor,p.id(),ref.runId());
   if(!a.status().equals("FINISHED"))throw ProjectAccessException.conflict("SOURCE_NOT_FINISHED","Finish the attempt before creating a frozen defect source");
   var step=a.steps().stream().filter(s->s.stepKey().equals(ref.stepKey())).findFirst().orElseThrow(ProjectAccessException::notFound);
   if(!Set.of("FAIL","BLOCKED").contains(step.conclusion()))throw ProjectAccessException.conflict("NOT_FAILED_SOURCE","Only FAIL or BLOCKED steps are defect sources");
   var m=run.manifest();result.add(new Link(UUID.randomUUID(),"RUN_STEP",null,null,m.sourceTestCaseId(),m.sourceRevisionId(),ref.runId(),ref.attemptId(),m.id(),ref.stepKey(),step.actualResult(),step.expected(),step.conclusion(),step.rowVersion()));
   var reqs=jdbc.query("SELECT l.requirement_id,l.requirement_revision_id FROM trace_link l JOIN requirement r ON r.tenant_id=l.tenant_id AND r.project_id=l.project_id AND r.id=l.requirement_id WHERE l.tenant_id=? AND l.project_id=? AND l.test_case_id=? AND l.test_revision_id=? AND l.state='ACTIVE' AND r.deleted_at IS NULL AND r.current_revision_id=l.requirement_revision_id ORDER BY l.requirement_id LIMIT 101",
    (rs,i)->requirementLink(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),p.tenantId(),p.id(),m.sourceTestCaseId(),m.sourceRevisionId());
   if(reqs.size()>100)throw ProjectAccessException.conflict("SOURCE_TRACE_LIMIT","Source has more than 100 current requirement links");result.addAll(reqs);
  }
  return result;
 }
 private static Link requirementLink(UUID req,UUID revision){return new Link(UUID.randomUUID(),"REQUIREMENT",req,revision,null,null,null,null,null,null,null,null,null,null);}
 public void insert(ProjectService.ProjectView p,UUID actor,UUID defect,List<Link> links){for(var l:links)
  jdbc.update("INSERT INTO defect_link(tenant_id,project_id,id,defect_id,kind,requirement_id,requirement_revision_id,test_case_id,test_revision_id,run_id,attempt_id,manifest_id,step_key,actual_result,expected_result,source_conclusion,source_version,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",p.tenantId(),p.id(),l.id(),defect,l.kind(),l.requirementId(),l.requirementRevisionId(),l.testCaseId(),l.testRevisionId(),l.runId(),l.attemptId(),l.manifestId(),l.stepKey(),l.actualResult(),l.expectedResult(),l.sourceConclusion(),l.sourceVersion(),actor);
 }
 public List<Link> links(ProjectService.ProjectView p,UUID id){return jdbc.query("SELECT * FROM defect_link WHERE tenant_id=? AND project_id=? AND defect_id=? ORDER BY kind,id LIMIT 101",DefectEvidence::link,p.tenantId(),p.id(),id);}
 public Verification verification(ProjectService.ProjectView p,UUID id){
  // REOPENED retains the old resolution as history, not as current eligibility.
  UUID verified=jdbc.queryForObject("SELECT CASE WHEN status IN ('RESOLVED','CLOSED') THEN app_defect_pass(tenant_id,project_id,id,resolved_revision_id) ELSE NULL END FROM defect WHERE tenant_id=? AND project_id=? AND id=?",UUID.class,p.tenantId(),p.id(),id);
  var source=links(p,id).stream().filter(l->l.kind().equals("RUN_STEP")).findFirst();
  return new Verification(verified!=null,source.map(Link::runId).orElse(null),verified,verified==null?"No completed related PASS in current resolution cycle":"Related retest PASS confirmed");
 }
 public RetestStart start(UUID actor,ProjectService.ProjectView p,UUID defect,String key){
  var source=links(p,defect).stream().filter(l->l.kind().equals("RUN_STEP")).findFirst().orElseThrow(()->ProjectAccessException.conflict("NO_TEST_SOURCE","This defect has no execution source"));
  var a=execution.rerun(actor,p.id(),source.runId(),CoreCommands.hash(defect.toString(),key));
  UUID resolution=jdbc.queryForObject("SELECT resolved_revision_id FROM defect WHERE tenant_id=? AND project_id=? AND id=?",UUID.class,p.tenantId(),p.id(),defect);
  jdbc.update("INSERT INTO defect_retest(tenant_id,project_id,id,defect_id,resolved_revision_id,run_id,attempt_id,created_by) VALUES(?,?,?,?,?,?,?,?)",p.tenantId(),p.id(),UUID.randomUUID(),defect,resolution,source.runId(),a.id(),actor);
  return new RetestStart(source.runId(),a.id());
 }
 public CoreCommands.Page<Retest> retests(ProjectService.ProjectView p,UUID id,String cursor,Integer limit){
  int n=CoreCommands.limit(limit);var rows=jdbc.query("SELECT v.id,v.run_id,v.attempt_id,v.resolved_revision_id,a.attempt_no,a.status,a.conclusion FROM defect_retest v JOIN run_attempt a ON a.tenant_id=v.tenant_id AND a.project_id=v.project_id AND a.id=v.attempt_id WHERE v.tenant_id=? AND v.project_id=? AND v.defect_id=? AND v.id>? ORDER BY v.id LIMIT ?",
   (rs,i)->new Retest(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getInt(5),rs.getString(6),rs.getString(7)),p.tenantId(),p.id(),id,CoreCommands.cursor(cursor),n+1);
  boolean more=rows.size()>n;var page=more?rows.subList(0,n):rows;return new CoreCommands.Page<>(page,more?page.get(n-1).id().toString():null);
 }
 private static Link link(ResultSet r,int i)throws SQLException{return new Link(r.getObject("id",UUID.class),r.getString("kind"),r.getObject("requirement_id",UUID.class),r.getObject("requirement_revision_id",UUID.class),r.getObject("test_case_id",UUID.class),r.getObject("test_revision_id",UUID.class),r.getObject("run_id",UUID.class),r.getObject("attempt_id",UUID.class),r.getObject("manifest_id",UUID.class),r.getObject("step_key",UUID.class),r.getString("actual_result"),r.getString("expected_result"),r.getString("source_conclusion"),r.getObject("source_version",Long.class));}
}
