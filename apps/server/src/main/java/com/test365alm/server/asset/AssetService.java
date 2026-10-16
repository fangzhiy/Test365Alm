package com.test365alm.server.asset;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.*;

/** Restricted runtime commands. External I/O is bounded, but never claimed to be DB-atomic. */
@Service
public class AssetService {
    private final JdbcTemplate jdbc; private final CoreCommands commands;
    private final BlobStore blobs; private final MalwareScanner scanner; private final ScanResultWriter scanWriter; private final TransactionTemplate tx;
    public AssetService(JdbcTemplate jdbc,CoreCommands commands,BlobStore blobs,MalwareScanner scanner,ScanResultWriter scanWriter,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.commands=commands;this.blobs=blobs;this.scanner=scanner;this.scanWriter=scanWriter;this.tx=new TransactionTemplate(manager);
        this.tx.setTimeout(180);
    }
    private ProjectService.ProjectView scope(UUID actor,UUID project,boolean write) {
        if(write)commands.lock(project);return commands.scope(actor,project,write,false);
    }
    public Upload createUpload(UUID actor,UUID project,JsonNode body,String key) {
        return tx.execute(t->{var p=scope(actor,project,true);var c=AssetRules.upload(body);
            String route="asset:upload:create",hash=CoreCommands.hash(CoreCommands.canonical(body));
            var replay=commands.replay(p,actor,route,key,hash,Upload.class);if(replay!=null)return replay;
            if(c.assetId()!=null)asset(p,c.assetId());
            UUID id=UUID.randomUUID(),candidate=c.assetId()==null?UUID.randomUUID():c.assetId(),revision=UUID.randomUUID();
            jdbc.update("INSERT INTO upload_session(tenant_id,project_id,id,existing_asset_id,candidate_asset_id,candidate_revision_id,quarantine_key,published_key,filename,declared_size,declared_sha256,declared_content_type,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",p.tenantId(),project,id,c.assetId(),candidate,revision,qkey(p,id),pkey(p,revision),c.filename(),c.size(),c.sha256(),c.contentType(),actor);
            var result=upload(p,id,actor,false).view();event(p,actor,id,null,null,null,"UPLOAD_SESSION_CREATED",Map.of("uploadId",id,"size",c.size(),"mime",c.contentType()));
            commands.save(p,actor,route,key,hash,result);return result;});
    }
    public Upload getUpload(UUID actor,UUID project,UUID id) {return tx.execute(t->upload(scope(actor,project,false),id,actor,false).view());}
    public CoreCommands.Page<Upload> uploads(UUID actor,UUID project,String cursor,Integer limit) {
        return tx.execute(t->{var p=scope(actor,project,false);int n=CoreCommands.limit(limit);
            return page(jdbc.query("SELECT * FROM upload_session WHERE tenant_id=? AND project_id=? AND created_by=? AND id>? ORDER BY id LIMIT ?",(r,i)->uploadRow(r,i).view(),p.tenantId(),project,actor,CoreCommands.cursor(cursor),n+1),n,Upload::id);});
    }
    public Receipt putContent(UUID actor,UUID project,UUID id,InputStream stream,String contentType) {
        return tx.execute(t->{var p=scope(actor,project,true);var row=upload(p,id,actor,true);var u=row.view();
            boolean confirmed=u.actualSize()!=null;
            if(contentType==null||!contentType.split(";",2)[0].trim().equals(u.declaredContentType()))throw mismatch(confirmed);
            try(var content=FileRules.inspect(stream,u.declaredSize(),u.declaredSha256(),u.declaredContentType())) {
                var receipt=new Receipt(id,"UPLOADED",content.size(),content.sha256(),content.mime());
                if(confirmed)return receipt;
                try(var data=Files.newInputStream(content.path())){blobs.putQuarantine(row.quarantineKey(),data,content.size());}
                jdbc.update("UPDATE upload_session SET actual_size=?,computed_sha256=?,detected_mime=?,state='UPLOADED',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",content.size(),content.sha256(),content.mime(),p.tenantId(),project,id);
                event(p,actor,id,null,null,null,"UPLOAD_CONTENT_CONFIRMED",Map.of("uploadId",id,"size",content.size(),"mime",content.mime()));return receipt;
            }catch(ProjectAccessException ex){if(confirmed&&Set.of("CONTENT_MISMATCH","UNSUPPORTED_FILE_TYPE").contains(ex.code()))throw mismatch(true);throw ex;}
            catch(IOException ex){throw storage();}
        });
    }
    public Upload complete(UUID actor,UUID project,UUID id,JsonNode body,String key) {
        Completion done=tx.execute(t->{var p=scope(actor,project,true);FieldRules.fields(body,Set.of());var row=upload(p,id,actor,true);var u=row.view();
            String route="asset:upload:complete:"+id,hash=CoreCommands.hash(CoreCommands.canonical(body));
            var replay=commands.replay(p,actor,route,key,hash,Upload.class);if(replay!=null)return new Completion(replay,null,row.quarantineKey());
            if(u.state().equals("REJECTED"))throw ProjectAccessException.conflict("UPLOAD_REJECTED","Infected upload cannot be published");
            if(u.state().equals("CREATED"))throw ProjectAccessException.conflict("UPLOAD_NOT_READY","Upload content has not been confirmed");
            if(u.state().equals("PUBLISHED")){commands.save(p,actor,route,key,hash,u);return new Completion(u,null,row.quarantineKey());}
            if(jdbc.queryForObject("SELECT count(*) FROM scan_result WHERE tenant_id=? AND project_id=? AND upload_id=? AND status='INFECTED'",Integer.class,p.tenantId(),project,id)>0)
                return rejectInfected(p,actor,id);
            // Revalidate the stored bytes, not just the original HTTP receipt.
            try(var raw=blobs.openQuarantine(row.quarantineKey());var content=FileRules.inspect(raw,u.declaredSize(),u.declaredSha256(),u.declaredContentType())) {
                MalwareScanner.ScanResult scan;
                try(var bytes=Files.newInputStream(content.path())){scan=scanner.scan(bytes);}
                // Durable scanner attestation uses a separate least-privileged writer, not runtime.
                // It may survive a failed publication, just like the privately stored candidate object.
                UUID scanId=scanWriter.record(p.tenantId(),project,id,actor,content.sha256(),scan);
                if(scan.status()==MalwareScanner.Status.ERROR) {
                    jdbc.update("UPDATE upload_session SET state='SCAN_FAILED',last_error_code='SCAN_UNAVAILABLE',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,id);
                    return new Completion(null,AssetRules.error("SCAN_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,"Scanner unavailable; upload remains quarantined"),null);
                }
                if(scan.status()==MalwareScanner.Status.INFECTED) {
                    return rejectInfected(p,actor,id);
                }
                if(scan.status()!=MalwareScanner.Status.CLEAN)throw new IllegalStateException("Unrecognized scan result");
                // Deterministic candidate key is recoverable even if a later DB statement fails.
                blobs.publish(row.quarantineKey(),row.publishedKey());
                boolean fresh=row.existingAssetId()==null;
                if(fresh)jdbc.update("INSERT INTO asset(tenant_id,project_id,id,current_revision_id,created_by) VALUES(?,?,?,?,?)",p.tenantId(),project,row.candidateAssetId(),row.candidateRevisionId(),actor);
                else jdbc.query("SELECT id FROM asset WHERE tenant_id=? AND project_id=? AND id=? FOR UPDATE",r->{},p.tenantId(),project,row.candidateAssetId());
                long number=jdbc.queryForObject("UPDATE asset SET next_revision_no=next_revision_no+1 WHERE tenant_id=? AND project_id=? AND id=? RETURNING next_revision_no-1",Long.class,p.tenantId(),project,row.candidateAssetId());
                jdbc.update("INSERT INTO asset_revision(tenant_id,project_id,asset_id,id,revision_no,upload_id,scan_result_id,object_key,size,sha256,mime,filename,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",p.tenantId(),project,row.candidateAssetId(),row.candidateRevisionId(),number,id,scanId,row.publishedKey(),content.size(),content.sha256(),content.mime(),u.filename(),actor);
                jdbc.update("UPDATE asset SET current_revision_id=? WHERE tenant_id=? AND project_id=? AND id=?",row.candidateRevisionId(),p.tenantId(),project,row.candidateAssetId());
                jdbc.update("UPDATE upload_session SET state='PUBLISHED',cleanup_pending=true,last_error_code=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,id);
                event(p,actor,id,row.candidateAssetId(),row.candidateRevisionId(),null,"UPLOAD_SCAN_CLEAN",Map.of("uploadId",id,"state","CLEAN"));
                if(fresh)event(p,actor,id,row.candidateAssetId(),row.candidateRevisionId(),null,"ASSET_CREATED",Map.of("assetId",row.candidateAssetId()));
                event(p,actor,id,row.candidateAssetId(),row.candidateRevisionId(),null,"ASSET_REVISION_PUBLISHED",Map.of("assetId",row.candidateAssetId(),"revisionId",row.candidateRevisionId(),"revisionNumber",number,"size",content.size(),"mime",content.mime()));
                var result=upload(p,id,actor,false).view();commands.save(p,actor,route,key,hash,result);return new Completion(result,null,row.quarantineKey());
            }catch(IOException ex){throw storage();}
        });
        // Returning an error out of the transaction commits SCAN_FAILED / REJECTED safely.
        if(done.error()!=null)throw done.error();
        cleanupAfterCommit(actor,project,id,done.quarantineKey());return done.result();
    }
    private Completion rejectInfected(ProjectService.ProjectView p,UUID actor,UUID id) {
        jdbc.update("UPDATE upload_session SET state='REJECTED',last_error_code='FILE_INFECTED',row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),p.id(),id);
        event(p,actor,id,null,null,null,"UPLOAD_SCAN_REJECTED",Map.of("uploadId",id,"state","REJECTED"));
        return new Completion(null,AssetRules.error("FILE_INFECTED",HttpStatus.UNPROCESSABLE_ENTITY,"Malware detected; upload rejected"),null);
    }
    private void cleanupAfterCommit(UUID actor,UUID project,UUID id,String key) {
        if(key==null)return;
        try {
            tx.execute(t->{var p=scope(actor,project,true);var row=upload(p,id,actor,true);
                if(!row.view().state().equals("PUBLISHED")||!row.view().cleanupPending())return null;
                blobs.deleteQuarantine(key);
                jdbc.update("UPDATE upload_session SET cleanup_pending=false,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,id);return null;});
        }catch(RuntimeException ex){
            // Publication already committed. Leave cleanup_pending=true; do not change its frozen response.
            // No raw object-store exception, credentials, or payload is logged.
        }
    }
    public Asset get(UUID actor,UUID project,UUID id){return tx.execute(t->asset(scope(actor,project,false),id));}
    public CoreCommands.Page<Asset> list(UUID actor,UUID project,String cursor,Integer limit) {
        return tx.execute(t->{var p=scope(actor,project,false);int n=CoreCommands.limit(limit);
            var ids=jdbc.query("SELECT id FROM asset WHERE tenant_id=? AND project_id=? AND id>? ORDER BY id LIMIT ?",(r,i)->r.getObject(1,UUID.class),p.tenantId(),project,CoreCommands.cursor(cursor),n+1);
            return page(ids.stream().map(id->asset(p,id)).toList(),n,Asset::id);});
    }
    public Revision revision(UUID actor,UUID project,UUID asset,UUID revision){return tx.execute(t->revision(scope(actor,project,false),asset,revision));}
    public CoreCommands.Page<Revision> revisions(UUID actor,UUID project,UUID asset,String cursor,Integer limit) {
        return tx.execute(t->{var p=scope(actor,project,false);asset(p,asset);int n=CoreCommands.limit(limit);
            return page(jdbc.query("SELECT * FROM asset_revision WHERE tenant_id=? AND project_id=? AND asset_id=? AND id>? ORDER BY id LIMIT ?",AssetService::revisionRow,p.tenantId(),project,asset,CoreCommands.cursor(cursor),n+1),n,Revision::id);});
    }
    public Download download(UUID actor,UUID project,UUID asset,UUID revision) {
        return tx.execute(t->{var p=scope(actor,project,false);var result=revision(p,asset,revision);
            String key=one(jdbc.query("SELECT object_key FROM asset_revision WHERE tenant_id=? AND project_id=? AND asset_id=? AND id=?",(r,i)->r.getString(1),p.tenantId(),project,asset,revision));
            return new Download(result,blobs.openPublished(key));});
    }
    public Attachment createAttachment(UUID actor,UUID project,JsonNode body,String key) {
        return tx.execute(t->{var p=scope(actor,project,true);var c=AssetRules.link(body);String route="asset:attachment:create",hash=CoreCommands.hash(CoreCommands.canonical(body));
            var replay=commands.replay(p,actor,route,key,hash,Attachment.class);if(replay!=null)return replay;
            target(p,c.kind(),c.target(),true);revision(p,c.assetId(),c.revisionId());String column=column(c.kind());
            var prior=jdbc.query("SELECT id FROM attachment_link WHERE tenant_id=? AND project_id=? AND kind=? AND "+column+"=? AND asset_revision_id=?",(r,i)->r.getObject(1,UUID.class),p.tenantId(),project,c.kind(),c.target(),c.revisionId());
            if(!prior.isEmpty()){var result=attachment(p,prior.get(0));commands.save(p,actor,route,key,hash,result);return result;}
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO attachment_link(tenant_id,project_id,id,kind,requirement_id,test_case_id,defect_id,asset_id,asset_revision_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",p.tenantId(),project,id,c.kind(),c.kind().equals("REQUIREMENT")?c.target():null,c.kind().equals("TEST_CASE")?c.target():null,c.kind().equals("DEFECT")?c.target():null,c.assetId(),c.revisionId(),actor);
            var result=attachment(p,id);event(p,actor,null,c.assetId(),c.revisionId(),id,"ATTACHMENT_LINK_CREATED",Map.of("linkId",id,"kind",c.kind(),"targetId",c.target(),"assetId",c.assetId(),"revisionId",c.revisionId()));
            commands.save(p,actor,route,key,hash,result);return result;});
    }
    public CoreCommands.Page<Attachment> attachments(UUID actor,UUID project,String kind,UUID target,String cursor,Integer limit) {
        return tx.execute(t->{var p=scope(actor,project,false);target(p,kind,target,false);int n=CoreCommands.limit(limit);
            var ids=jdbc.query("SELECT id FROM attachment_link WHERE tenant_id=? AND project_id=? AND kind=? AND "+column(kind)+"=? AND id>? ORDER BY id LIMIT ?",(r,i)->r.getObject(1,UUID.class),p.tenantId(),project,kind,target,CoreCommands.cursor(cursor),n+1);
            return page(ids.stream().map(id->attachment(p,id)).toList(),n,Attachment::id);});
    }
    private void target(ProjectService.ProjectView p,String kind,UUID id,boolean write) {
        String table=switch(kind){case "REQUIREMENT"->"requirement";case "TEST_CASE"->"test_case";case "DEFECT"->"defect";default->throw ProjectAccessException.invalid("Unsupported attachment kind");};
        if(id==null)throw ProjectAccessException.invalid("Typed target is required");
        one(jdbc.query("SELECT id FROM "+table+" WHERE tenant_id=? AND project_id=? AND id=?"+(kind.equals("REQUIREMENT")&&write?" AND deleted_at IS NULL":""),(r,i)->r.getObject(1,UUID.class),p.tenantId(),p.id(),id));
    }
    private static String column(String kind){return switch(kind){case "REQUIREMENT"->"requirement_id";case "TEST_CASE"->"test_case_id";case "DEFECT"->"defect_id";default->throw ProjectAccessException.invalid("Unsupported attachment kind");};}
    private Session upload(ProjectService.ProjectView p,UUID id,UUID actor,boolean lock) {
        // NO KEY UPDATE keeps the identity locked yet permits the independent scanner FK's KEY SHARE.
        return one(jdbc.query("SELECT * FROM upload_session WHERE tenant_id=? AND project_id=? AND id=? AND created_by=?"+(lock?" FOR NO KEY UPDATE":""),AssetService::uploadRow,p.tenantId(),p.id(),id,actor));
    }
    private Asset asset(ProjectService.ProjectView p,UUID id) {
        return one(jdbc.query("SELECT * FROM asset WHERE tenant_id=? AND project_id=? AND id=?",(r,i)->new Asset(id,p.id(),r.getObject("current_revision_id",UUID.class),revision(p,id,r.getObject("current_revision_id",UUID.class)),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class)),p.tenantId(),p.id(),id));
    }
    private Revision revision(ProjectService.ProjectView p,UUID asset,UUID revision){return one(jdbc.query("SELECT * FROM asset_revision WHERE tenant_id=? AND project_id=? AND asset_id=? AND id=?",AssetService::revisionRow,p.tenantId(),p.id(),asset,revision));}
    private Attachment attachment(ProjectService.ProjectView p,UUID id) {
        return one(jdbc.query("SELECT * FROM attachment_link WHERE tenant_id=? AND project_id=? AND id=?",(r,i)->new Attachment(id,p.id(),r.getString("kind"),r.getObject("requirement_id",UUID.class),r.getObject("test_case_id",UUID.class),r.getObject("defect_id",UUID.class),r.getObject("asset_id",UUID.class),r.getObject("asset_revision_id",UUID.class),revision(p,r.getObject("asset_id",UUID.class),r.getObject("asset_revision_id",UUID.class)),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class)),p.tenantId(),p.id(),id));
    }
    private void event(ProjectService.ProjectView p,UUID actor,UUID upload,UUID asset,UUID revision,UUID link,String action,Object payload) {
        String json=CoreCommands.JSON.writeValueAsString(payload);
        jdbc.update("INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,?,?,1,?)",p.tenantId(),p.id(),actor,action,link==null?"upload_session":"attachment_link",link==null?upload:link,CoreCommands.hash(json));
        jdbc.update("INSERT INTO asset_outbox_event(tenant_id,project_id,upload_id,asset_id,revision_id,link_id,event_type,payload) VALUES(?,?,?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),upload,asset,revision,link,action,json);
    }
    private static Session uploadRow(ResultSet r,int i)throws SQLException {
        String state=r.getString("state");UUID candidate=r.getObject("candidate_asset_id",UUID.class),existing=r.getObject("existing_asset_id",UUID.class),revision=r.getObject("candidate_revision_id",UUID.class);
        return new Session(new Upload(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),state.equals("PUBLISHED")?candidate:existing,state.equals("PUBLISHED")?revision:null,r.getString("filename"),r.getLong("declared_size"),r.getString("declared_sha256"),r.getString("declared_content_type"),(Long)r.getObject("actual_size"),r.getString("computed_sha256"),r.getString("detected_mime"),state,r.getBoolean("cleanup_pending"),r.getString("last_error_code"),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class),r.getLong("row_version")),existing,candidate,revision,r.getString("quarantine_key"),r.getString("published_key"));
    }
    private static Revision revisionRow(ResultSet r,int i)throws SQLException {return new Revision(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("asset_id",UUID.class),r.getLong("revision_no"),r.getString("filename"),r.getLong("size"),r.getString("sha256"),r.getString("mime"),"CLEAN",r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class));}
    private static String qkey(ProjectService.ProjectView p,UUID upload){return "quarantine/"+p.tenantId()+"/"+p.id()+"/"+upload;}
    private static String pkey(ProjectService.ProjectView p,UUID revision){return "published/"+p.tenantId()+"/"+p.id()+"/"+revision;}
    private static ProjectAccessException mismatch(boolean confirmed){return confirmed?ProjectAccessException.conflict("UPLOAD_CONTENT_CONFLICT","Upload already confirmed with different bytes"):AssetRules.error("CONTENT_MISMATCH",HttpStatus.UNPROCESSABLE_ENTITY,"Content does not match its declaration");}
    private static ProjectAccessException storage(){return AssetRules.error("STORAGE_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE,"Storage unavailable; retry the same upload intent");}
    private static <T>T one(List<T> values){if(values.isEmpty())throw ProjectAccessException.notFound();return values.get(0);}
    private static <T>CoreCommands.Page<T> page(List<T> values,int n,Function<T,UUID> id){boolean more=values.size()>n;var list=more?List.copyOf(values.subList(0,n)):List.copyOf(values);return new CoreCommands.Page<>(list,more?id.apply(list.get(n-1)).toString():null);}
    private record Completion(Upload result,ProjectAccessException error,String quarantineKey) { }
    private record Session(Upload view,UUID existingAssetId,UUID candidateAssetId,UUID candidateRevisionId,String quarantineKey,String publishedKey) { }
    public record Upload(UUID id,UUID projectId,UUID assetId,UUID revisionId,String filename,long declaredSize,String declaredSha256,String declaredContentType,Long actualSize,String computedSha256,String detectedMime,String state,boolean cleanupPending,String lastErrorCode,UUID createdBy,OffsetDateTime createdAt,long rowVersion) { }
    public record Receipt(UUID uploadId,String state,long size,String sha256,String mime) { }
    public record Revision(UUID id,UUID projectId,UUID assetId,long revisionNumber,String filename,long size,String sha256,String mime,String scanStatus,UUID createdBy,OffsetDateTime createdAt) { }
    public record Asset(UUID id,UUID projectId,UUID currentRevisionId,Revision currentRevision,UUID createdBy,OffsetDateTime createdAt) { }
    public record Attachment(UUID id,UUID projectId,String kind,UUID requirementId,UUID testCaseId,UUID defectId,UUID assetId,UUID assetRevisionId,Revision revision,UUID createdBy,OffsetDateTime createdAt) { }
    public record Download(Revision revision,InputStream stream) { }
}
