package com.test365alm.server.metadata;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;

@Service
public class FieldDefinitionService {
    private final JdbcTemplate jdbc; private final CoreCommands commands;
    public FieldDefinitionService(JdbcTemplate jdbc,CoreCommands commands){this.jdbc=jdbc;this.commands=commands;}
    @Transactional public FieldView create(UUID actor,UUID project,JsonNode definition,String key) {
        commands.lock(project);var p=commands.scope(actor,project,true,true);FieldRules.definition(definition);
        String hash=CoreCommands.hash(CoreCommands.canonical(definition));
        var replay=commands.replay(p,actor,"field:create",key,hash,FieldView.class);if(replay!=null)return replay;
        if(jdbc.queryForObject("SELECT count(*) FROM field_definition WHERE tenant_id=? AND project_id=? AND field_key=?",Integer.class,p.tenantId(),project,definition.get("key").textValue())>0)
            throw ProjectAccessException.conflict("DUPLICATE_FIELD","Field key already exists");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO field_definition(tenant_id,project_id,id,field_key,data_type,row_version,created_by) VALUES(?,?,?,?,?,1,?)",p.tenantId(),project,id,definition.get("key").textValue(),definition.get("type").textValue(),actor);
        append(p.tenantId(),project,id,1,definition,actor);
        var result=new FieldView(id,project,"CUSTOM",1,definition);
        commands.event(p,actor,"field_definition",id,1,"field.created",result);commands.save(p,actor,"field:create",key,hash,result);return result;
    }
    @Transactional public FieldView revise(UUID actor,UUID project,UUID id,JsonNode definition,String ifMatch,String key) {
        commands.lock(project);var p=commands.scope(actor,project,true,true);long expected=CoreCommands.version(ifMatch);FieldRules.definition(definition);
        String route="field:revise:"+id,hash=CoreCommands.hash(Long.toString(expected),CoreCommands.canonical(definition));
        var replay=commands.replay(p,actor,route,key,hash,FieldView.class);if(replay!=null)return replay;
        var old=get(actor,project,id,null);
        if(old.rowVersion()!=expected)throw ProjectAccessException.preconditionFailed("STALE_VERSION","Definition changed");
        if(!old.definition().get("key").equals(definition.get("key")) || !old.definition().get("type").equals(definition.get("type")))throw ProjectAccessException.invalid("key and type are immutable");
        append(p.tenantId(),project,id,expected+1,definition,actor);
        jdbc.update("UPDATE field_definition SET row_version=row_version+1 WHERE tenant_id=? AND project_id=? AND id=?",p.tenantId(),project,id);
        var result=new FieldView(id,project,"CUSTOM",expected+1,definition);
        commands.event(p,actor,"field_definition",id,result.rowVersion(),"field.revised",result);commands.save(p,actor,route,key,hash,result);return result;
    }
    @Transactional(readOnly=true) public FieldView get(UUID actor,UUID project,UUID id,Long revision) {
        var p=commands.scope(actor,project,false,false);
        var rows=jdbc.query("SELECT f.id,r.revision_no,r.definition::text FROM field_definition f JOIN field_definition_revision r ON r.tenant_id=f.tenant_id AND r.project_id=f.project_id AND r.field_id=f.id AND r.revision_no=COALESCE(?,f.row_version) WHERE f.tenant_id=? AND f.project_id=? AND f.id=?",
            (rs,i)->new FieldView(rs.getObject(1,UUID.class),project,"CUSTOM",rs.getLong(2),CoreCommands.JSON.readTree(rs.getString(3))),revision,p.tenantId(),project,id);
        if(rows.isEmpty())throw ProjectAccessException.notFound();return rows.get(0);
    }
    @Transactional(readOnly=true) public CoreCommands.Page<FieldView> list(UUID actor,UUID project,String cursor,Integer limit) {
        var p=commands.scope(actor,project,false,false);int n=CoreCommands.limit(limit);UUID after=CoreCommands.cursor(cursor);
        var rows=jdbc.query("SELECT f.id,f.row_version,r.definition::text FROM field_definition f JOIN field_definition_revision r ON r.tenant_id=f.tenant_id AND r.project_id=f.project_id AND r.field_id=f.id AND r.revision_no=f.row_version WHERE f.tenant_id=? AND f.project_id=? AND f.id>? ORDER BY f.id LIMIT ?",
            (rs,i)->new FieldView(rs.getObject(1,UUID.class),project,"CUSTOM",rs.getLong(2),CoreCommands.JSON.readTree(rs.getString(3))),p.tenantId(),project,after,n+1);
        boolean more=rows.size()>n;var items=more?rows.subList(0,n):rows;return new CoreCommands.Page<>(items,more?items.get(n-1).id().toString():null);
    }
    @Transactional(readOnly=true) public Validation validate(UUID actor,UUID project,UUID id,long revision,JsonNode value) {
        if(revision<1)throw ProjectAccessException.invalid("Positive revision required");
        return new Validation(true,revision,FieldRules.value(get(actor,project,id,revision).definition(),value));
    }
    @Transactional(readOnly=true) public java.util.List<SystemField> system(UUID actor,UUID project) {
        commands.scope(actor,project,false,false);return FieldRules.RESERVED.stream().sorted().map(k->new SystemField(k,"SYSTEM")).toList();
    }
    private void append(UUID tenant,UUID project,UUID id,long version,JsonNode d,UUID actor){jdbc.update("INSERT INTO field_definition_revision(tenant_id,project_id,field_id,revision_no,definition,created_by) VALUES(?,?,?,?,?::jsonb,?)",tenant,project,id,version,d.toString(),actor);}
    public record FieldView(UUID id,UUID projectId,String kind,long rowVersion,JsonNode definition) { }
    public record Validation(boolean valid,long revision,JsonNode value) { }
    public record SystemField(String key,String kind) { }
}
