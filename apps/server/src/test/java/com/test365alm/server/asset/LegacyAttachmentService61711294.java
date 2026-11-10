package com.test365alm.server.asset;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.*;

/** Test-only V20/V21 attachment SQL, extracted from 61711294ff10e16b8df29418badaa8b455bc80ea.
 * Only create/list attachment and their private dependencies override current code.
 * The current production storage, real scan, completion and download paths are inherited unchanged.
 */
public class LegacyAttachmentService61711294 extends AssetService {
    private final JdbcTemplate jdbc; private final CoreCommands commands; private final TransactionTemplate tx;
    public LegacyAttachmentService61711294(JdbcTemplate jdbc,CoreCommands commands,BlobStore blobs,MalwareScanner scanner,ScanResultWriter writer,PlatformTransactionManager manager) {
        super(jdbc,commands,blobs,scanner,writer,manager);
        this.jdbc=jdbc; this.commands=commands; this.tx=new TransactionTemplate(manager); this.tx.setTimeout(180);
    }
    private ProjectService.ProjectView scope(UUID actor,UUID project,boolean write) {
        if(write)commands.lock(project);return commands.scope(actor,project,write,false);
    }

    public Attachment createAttachment(UUID actor,UUID project,JsonNode body,String key) {
        return tx.execute(t->{var p=scope(actor,project,true);var c=AssetRules.link(body);String route="asset:attachment:create",hash=CoreCommands.hash(CoreCommands.canonical(body));
            var replay=commands.replay(p,actor,route,key,hash,Attachment.class);if(replay!=null)return replay;
            target(p,c.kind(),c.target(),true);revision(p,c.assetId(),c.revisionId());String column=column(c.kind());
            var prior=jdbc.query("SELECT id FROM attachment_link WHERE tenant_id=? AND project_id=? AND kind=? AND "+column+"=? AND asset_revision_id=?",(r,i)->r.getObject(1,UUID.class),p.tenantId(),project,c.kind(),c.target(),c.revisionId());
            if(!prior.isEmpty()){var result=attachment(p,prior.get(0));commands.save(p,actor,route,key,hash,FrozenAttachment.from(result));return result;}
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO attachment_link(tenant_id,project_id,id,kind,requirement_id,test_case_id,defect_id,asset_id,asset_revision_id,created_by) VALUES(?,?,?,?,?,?,?,?,?,?)",p.tenantId(),project,id,c.kind(),c.kind().equals("REQUIREMENT")?c.target():null,c.kind().equals("TEST_CASE")?c.target():null,c.kind().equals("DEFECT")?c.target():null,c.assetId(),c.revisionId(),actor);
            var result=attachment(p,id);event(p,actor,null,c.assetId(),c.revisionId(),id,"ATTACHMENT_LINK_CREATED",Map.of("linkId",id,"kind",c.kind(),"targetId",c.target(),"assetId",c.assetId(),"revisionId",c.revisionId()));
            commands.save(p,actor,route,key,hash,FrozenAttachment.from(result));return result;});
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

    private Attachment attachment(ProjectService.ProjectView p,UUID id) {
        return one(jdbc.query("SELECT * FROM attachment_link WHERE tenant_id=? AND project_id=? AND id=?",(r,i)->new Attachment(id,p.id(),r.getString("kind"),r.getObject("requirement_id",UUID.class),r.getObject("test_case_id",UUID.class),r.getObject("defect_id",UUID.class),null,r.getObject("asset_id",UUID.class),r.getObject("asset_revision_id",UUID.class),revision(p,r.getObject("asset_id",UUID.class),r.getObject("asset_revision_id",UUID.class)),r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class)),p.tenantId(),p.id(),id));
    }

    private void event(ProjectService.ProjectView p,UUID actor,UUID upload,UUID asset,UUID revision,UUID link,String action,Object payload) {
        String json=CoreCommands.JSON.writeValueAsString(payload);
        jdbc.update("INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,?,?,1,?)",p.tenantId(),p.id(),actor,action,link==null?"upload_session":"attachment_link",link==null?upload:link,CoreCommands.hash(json));
        jdbc.update("INSERT INTO asset_outbox_event(tenant_id,project_id,upload_id,asset_id,revision_id,link_id,event_type,payload) VALUES(?,?,?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),upload,asset,revision,link,action,json);
    }

    private static Revision revisionRow(ResultSet r,int i)throws SQLException {return new Revision(r.getObject("id",UUID.class),r.getObject("project_id",UUID.class),r.getObject("asset_id",UUID.class),r.getLong("revision_no"),r.getString("filename"),r.getLong("size"),r.getString("sha256"),r.getString("mime"),"CLEAN",r.getObject("created_by",UUID.class),r.getObject("created_at",OffsetDateTime.class));}

    private static <T>T one(List<T> values){if(values.isEmpty())throw ProjectAccessException.notFound();return values.get(0);}

    private static <T>CoreCommands.Page<T> page(List<T> values,int n,Function<T,UUID> id){boolean more=values.size()>n;var list=more?List.copyOf(values.subList(0,n)):List.copyOf(values);return new CoreCommands.Page<>(list,more?id.apply(list.get(n-1)).toString():null);}

    private Revision revision(ProjectService.ProjectView p,UUID asset,UUID revision){return one(jdbc.query("SELECT * FROM asset_revision WHERE tenant_id=? AND project_id=? AND asset_id=? AND id=?",LegacyAttachmentService61711294::revisionRow,p.tenantId(),p.id(),asset,revision));}
    // Persist the genuine old response shape; the new nullable runStepId is not part of V20 JSON.
    private record FrozenAttachment(UUID id,UUID projectId,String kind,UUID requirementId,UUID testCaseId,UUID defectId,
            UUID assetId,UUID assetRevisionId,Revision revision,UUID createdBy,OffsetDateTime createdAt) {
        static FrozenAttachment from(Attachment a) { return new FrozenAttachment(a.id(),a.projectId(),a.kind(),a.requirementId(),a.testCaseId(),a.defectId(),a.assetId(),a.assetRevisionId(),a.revision(),a.createdBy(),a.createdAt()); }
    }
}
