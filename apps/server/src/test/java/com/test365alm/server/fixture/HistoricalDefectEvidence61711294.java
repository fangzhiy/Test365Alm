package com.test365alm.server.fixture;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.test365alm.server.defect.DefectEvidence;
import com.test365alm.server.execution.ExecutionService;
import com.test365alm.server.requirement.RequirementService;
import com.test365alm.server.project.*;

/**
 * Test-only prepare/requirementLink frozen from DefectEvidence at
 * 61711294ff10e16b8df29418badaa8b455bc80ea. No component annotation.
 * The only dependency substitution is the matching historical execution reader.
 * Inherited insert/links/verification/retests have unchanged SQL at that source.
 * Explicit old-target registration only; latest uses production DefectEvidence.
 */
public class HistoricalDefectEvidence61711294 extends DefectEvidence {
 private final JdbcTemplate jdbc;
 private final HistoricalExecutionService61711294 execution;
 private final RequirementService requirements;
 public HistoricalDefectEvidence61711294(JdbcTemplate jdbc,ExecutionService current,
   HistoricalExecutionService61711294 execution,RequirementService requirements){
  super(jdbc,current,requirements);this.jdbc=jdbc;this.execution=execution;this.requirements=requirements;
 }
 @Override
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

 /** Historical seeds need no retest command; never fall through to a current
  * execution writer against an old schema if a future fixture calls it. */
 @Override public RetestStart start(UUID actor,ProjectService.ProjectView p,UUID defect,String key){
  throw new IllegalStateException("Historical execution fixture does not support retest creation");
 }
}
