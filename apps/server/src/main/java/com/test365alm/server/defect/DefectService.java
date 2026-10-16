package com.test365alm.server.defect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.*;

/** All commands run on the restricted datasource inside one advised transaction. */
@Service
public class DefectService {
 private final JdbcTemplate jdbc;private final CoreCommands commands;private final DefectEvidence evidence;private final ProjectService projects;
 public DefectService(JdbcTemplate jdbc,CoreCommands commands,DefectEvidence evidence,ProjectService projects){this.jdbc=jdbc;this.commands=commands;this.evidence=evidence;this.projects=projects;}
 private ProjectService.ProjectView scope(UUID actor,UUID project,boolean write){
  if(write)projects.requireDefectWriteAccess(actor,project);
  return commands.scope(actor,project,write,false);
 }
 @Transactional public DefectView create(UUID actor,UUID project,JsonNode body,UUID requirement,String key){
  return createCommand(actor,project,body,requirement,null,key);
 }
 @Transactional public DefectView fromStep(UUID actor,UUID project,JsonNode body,UUID run,UUID attempt,UUID step,String key){
  return createCommand(actor,project,body,null,new DefectEvidence.SourceRef(run,attempt,step),key);
 }
 private DefectView createCommand(UUID actor,UUID project,JsonNode body,UUID requirement,DefectEvidence.SourceRef source,String key){
  commands.lock(project);var p=scope(actor,project,true);var c=DefectRules.content(body);
  String route=source==null?"defect:create":"defect:from-step",hash=CoreCommands.hash(CoreCommands.canonical(body),requirement==null?null:requirement.toString(),source==null?null:source.toString());
  var replay=commands.replay(p,actor,route,key,hash,DefectView.class);if(replay!=null)return replay;
  var sources=evidence.prepare(actor,p,requirement,source);
  jdbc.update("INSERT INTO defect_number_allocator(tenant_id,project_id) VALUES(?,?) ON CONFLICT DO NOTHING",p.tenantId(),project);
  long number=jdbc.queryForObject("UPDATE defect_number_allocator SET next_number=next_number+1 WHERE tenant_id=? AND project_id=? RETURNING next_number-1",Long.class,p.tenantId(),project);
  UUID id=UUID.randomUUID(),revision=UUID.randomUUID();
  jdbc.update("INSERT INTO defect(tenant_id,project_id,id,display_number,row_version,status,title,severity,priority,current_revision_id,created_by) VALUES(?,?,?,?,1,'OPEN',?,?,?,?,?)",p.tenantId(),project,id,number,c.title(),c.severity(),c.priority(),revision,actor);
  append(p,actor,id,revision,1,c,"OPEN","");evidence.insert(p,actor,id,sources);
  var result=read(p,id);event(p,actor,result,"defect.created");commands.save(p,actor,route,key,hash,result);return result;
 }
 @Transactional public DefectView edit(UUID actor,UUID project,UUID id,JsonNode body,String tag,String key){
  commands.lock(project);var p=scope(actor,project,true);long version=CoreCommands.version(tag);var c=DefectRules.content(body);
  String route="defect:edit:"+id,hash=CoreCommands.hash(Long.toString(version),CoreCommands.canonical(body));
  var replay=commands.replay(p,actor,route,key,hash,DefectView.class);if(replay!=null)return replay;
  var old=read(p,id);checkVersion(old,version);
  if(old.currentRevision().status().equals("CLOSED"))throw ProjectAccessException.conflict("DEFECT_CLOSED","Closed defect is immutable");
  UUID revision=UUID.randomUUID();append(p,actor,id,revision,version+1,c,old.currentRevision().status(),old.currentRevision().reason());
  update(p,id,revision,version+1,c,old.currentRevision().status(),null,false);
  var result=read(p,id);event(p,actor,result,"defect.revised");commands.save(p,actor,route,key,hash,result);return result;
 }
 @Transactional public DefectView transition(UUID actor,UUID project,UUID id,String status,String reason,String tag,String key){
  commands.lock(project);var p=scope(actor,project,true);long version=CoreCommands.version(tag);
  String route="defect:transition:"+id,hash=CoreCommands.hash(Long.toString(version),status,reason);
  var replay=commands.replay(p,actor,route,key,hash,DefectView.class);if(replay!=null)return replay;
  var old=read(p,id);checkVersion(old,version);var r=old.currentRevision();DefectRules.transition(r.status(),status,reason);
  if(status.equals("CLOSED") && old.links().stream().anyMatch(l->l.kind().equals("RUN_STEP")) && !evidence.verification(p,id).eligible())
   throw ProjectAccessException.conflict("RETEST_PASS_REQUIRED","A successful related retest in this resolution cycle is required");
  var c=new DefectRules.Content(r.title(),r.description(),r.severity(),r.priority());UUID revision=UUID.randomUUID();
  append(p,actor,id,revision,version+1,c,status,reason);update(p,id,revision,version+1,c,status,revision,status.equals("RESOLVED"));
  var result=read(p,id);event(p,actor,result,"defect.transitioned");commands.save(p,actor,route,key,hash,result);return result;
 }
 @Transactional public DefectEvidence.RetestStart retest(UUID actor,UUID project,UUID id,String tag,String key){
  commands.lock(project);var p=scope(actor,project,true);long version=CoreCommands.version(tag);
  String route="defect:retest:"+id,hash=CoreCommands.hash(Long.toString(version));
  var replay=commands.replay(p,actor,route,key,hash,DefectEvidence.RetestStart.class);if(replay!=null)return replay;
  var old=read(p,id);checkVersion(old,version);if(!old.currentRevision().status().equals("RESOLVED"))throw ProjectAccessException.conflict("INVALID_TRANSITION","Resolve defect before retest");
  var result=evidence.start(actor,p,id,key);event(p,actor,old,"defect.retest-started");commands.save(p,actor,route,key,hash,result);return result;
 }
 @Transactional(readOnly=true) public DefectView get(UUID actor,UUID project,UUID id){return read(scope(actor,project,false),id);}
 @Transactional(readOnly=true) public CoreCommands.Page<DefectView> list(UUID actor,UUID project,String q,String cursor,Integer limit){
  var p=scope(actor,project,false);int n=CoreCommands.limit(limit);String query=q==null?"":q;
  if(query.length()>200)throw ProjectAccessException.invalid("q exceeds 200 characters");
  var ids=jdbc.query("SELECT id FROM defect WHERE tenant_id=? AND project_id=? AND id>? AND position(lower(?) in lower(title))>0 ORDER BY id LIMIT ?",(rs,i)->rs.getObject(1,UUID.class),p.tenantId(),project,CoreCommands.cursor(cursor),query,n+1);
  boolean more=ids.size()>n;var page=more?ids.subList(0,n):ids;
  return new CoreCommands.Page<>(page.stream().map(id->read(p,id)).toList(),more?page.get(n-1).toString():null);
 }
 @Transactional(readOnly=true) public CoreCommands.Page<Revision> history(UUID actor,UUID project,UUID id,String cursor,Integer limit){
  var p=scope(actor,project,false);read(p,id);int n=CoreCommands.limit(limit);long after=0;
  if(cursor!=null)try{after=Long.parseLong(cursor);if(after<1)throw new NumberFormatException();}catch(NumberFormatException ex){throw ProjectAccessException.invalid("Invalid revision cursor");}
  var rows=jdbc.query("SELECT * FROM defect_revision WHERE tenant_id=? AND project_id=? AND defect_id=? AND revision_no>? ORDER BY revision_no LIMIT ?",DefectService::revision,p.tenantId(),project,id,after,n+1);
  boolean more=rows.size()>n;var page=more?rows.subList(0,n):rows;return new CoreCommands.Page<>(page,more?Long.toString(page.get(n-1).revisionNo()):null);
 }
 @Transactional(readOnly=true) public Revision revision(UUID actor,UUID project,UUID id,UUID revision){
  var p=scope(actor,project,false);read(p,id);var rows=jdbc.query("SELECT * FROM defect_revision WHERE tenant_id=? AND project_id=? AND defect_id=? AND id=?",DefectService::revision,p.tenantId(),project,id,revision);
  if(rows.isEmpty())throw ProjectAccessException.notFound();return rows.get(0);
 }
 @Transactional(readOnly=true) public DefectEvidence.Verification verification(UUID actor,UUID project,UUID id){var p=scope(actor,project,false);read(p,id);return evidence.verification(p,id);}
 @Transactional(readOnly=true) public CoreCommands.Page<DefectEvidence.Retest> retests(UUID actor,UUID project,UUID id,String cursor,Integer limit){var p=scope(actor,project,false);read(p,id);return evidence.retests(p,id,cursor,limit);}
 private DefectView read(ProjectService.ProjectView p,UUID id){
  var rows=jdbc.query("SELECT * FROM defect WHERE tenant_id=? AND project_id=? AND id=?",(rs,i)->new DefectView(id,p.id(),rs.getLong("display_number"),rs.getLong("row_version"),rs.getObject("created_by",UUID.class),rs.getObject("created_at",OffsetDateTime.class),null,List.of()),p.tenantId(),p.id(),id);
  if(rows.isEmpty())throw ProjectAccessException.notFound();var d=rows.get(0);
  var r=jdbc.queryForObject("SELECT * FROM defect_revision WHERE tenant_id=? AND project_id=? AND defect_id=? AND revision_no=?",DefectService::revision,p.tenantId(),p.id(),id,d.rowVersion());
  return new DefectView(id,p.id(),d.displayNumber(),d.rowVersion(),d.createdBy(),d.createdAt(),r,evidence.links(p,id));
 }
 private void append(ProjectService.ProjectView p,UUID actor,UUID id,UUID revision,long version,DefectRules.Content c,String status,String reason){
  jdbc.update("INSERT INTO defect_revision(tenant_id,project_id,defect_id,id,revision_no,title,description,severity,priority,status,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",p.tenantId(),p.id(),id,revision,version,c.title(),c.description(),c.severity(),c.priority(),status,reason,actor);
 }
 private void update(ProjectService.ProjectView p,UUID id,UUID revision,long version,DefectRules.Content c,String status,UUID resolution,boolean resolving){
  jdbc.update("UPDATE defect SET row_version=?,current_revision_id=?,title=?,severity=?,priority=?,status=?,resolved_revision_id=CASE WHEN ? THEN ? ELSE resolved_revision_id END WHERE tenant_id=? AND project_id=? AND id=?",version,revision,c.title(),c.severity(),c.priority(),status,resolving,resolution,p.tenantId(),p.id(),id);
 }
 private void event(ProjectService.ProjectView p,UUID actor,DefectView d,String action){
  String json=CoreCommands.JSON.writeValueAsString(d);
  jdbc.update("INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,'defect',?,?,?)",p.tenantId(),p.id(),actor,action,d.id(),d.rowVersion(),CoreCommands.hash(json));
  jdbc.update("INSERT INTO defect_outbox_event(tenant_id,project_id,defect_id,object_revision,event_type,payload) VALUES(?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),d.id(),d.currentRevision().id(),action,json);
 }
 private static void checkVersion(DefectView d,long v){if(d.rowVersion()!=v)throw ProjectAccessException.preconditionFailed("STALE_VERSION","Defect changed; read latest explicitly");}
 private static Revision revision(ResultSet r,int i)throws SQLException{return new Revision(r.getObject("id",UUID.class),r.getObject("defect_id",UUID.class),r.getLong("revision_no"),r.getString("title"),r.getString("description"),r.getString("severity"),r.getString("priority"),r.getString("status"),r.getString("reason"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class));}
 public record Revision(UUID id,UUID defectId,long revisionNo,String title,String description,String severity,String priority,String status,String reason,UUID createdBy,OffsetDateTime createdAt){}
 public record DefectView(UUID id,UUID projectId,long displayNumber,long rowVersion,UUID createdBy,OffsetDateTime createdAt,Revision currentRevision,List<DefectEvidence.Link> links){}
}
