package com.test365alm.server.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/** Transaction-scoped helpers, called only inside the application service's transaction. */
@Component
public class CoreCommands {
    public static final JsonMapper JSON=JsonMapper.builder().findAndAddModules().build();
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    public CoreCommands(JdbcTemplate jdbc,ProjectService projects) {this.jdbc=jdbc; this.projects=projects;}
    public ProjectService.ProjectView scope(UUID actor,UUID project,boolean write,boolean admin) {
        var p=write?projects.requireTestWriteAccess(actor,project):projects.getProject(actor,project);
        if(admin && !projects.permissions(actor,project).permissions().contains("project:manage-members")) throw ProjectAccessException.forbidden();
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id',?,true)",String.class,p.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('test365alm.project_id',?,true)",String.class,project.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id',?,true)",String.class,actor.toString());
        return p;
    }
    public void lock(UUID project) {jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",rs->{},project.toString());}
    public <T> T replay(ProjectService.ProjectView p, UUID actor,String route,String key,String hash,Class<T> type) {
        if(key==null || key.isBlank()) throw ProjectAccessException.preconditionRequired("Idempotency-Key is required");
        if(key.length()<8 || key.length()>128) throw ProjectAccessException.invalid("Invalid Idempotency-Key length");
        jdbc.update("DELETE FROM core_command_idempotency WHERE tenant_id=? AND project_id=? AND principal_id=? AND route=? AND idempotency_key=? AND expires_at<=CURRENT_TIMESTAMP",p.tenantId(),p.id(),actor,route,key);
        var rows=jdbc.query("SELECT request_hash,response_json::text FROM core_command_idempotency WHERE tenant_id=? AND project_id=? AND principal_id=? AND route=? AND idempotency_key=?",(rs,i)->new String[]{rs.getString(1),rs.getString(2)},p.tenantId(),p.id(),actor,route,key);
        if(rows.isEmpty()) return null;
        if(!rows.get(0)[0].equals(hash)) throw ProjectAccessException.conflict("IDEMPOTENCY_KEY_REUSED","Key was used for another request");
        return JSON.readValue(rows.get(0)[1],type);
    }
    public void save(ProjectService.ProjectView p,UUID actor,String route,String key,String hash,Object response) {
        jdbc.update("INSERT INTO core_command_idempotency(tenant_id,project_id,principal_id,route,idempotency_key,request_hash,response_json) VALUES(?,?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),actor,route,key,hash,JSON.writeValueAsString(response));
    }
    public void event(ProjectService.ProjectView p,UUID actor,String type,UUID id,long version,String action,Object snapshot) {
        jdbc.update("INSERT INTO audit_event(tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,?,?,?,?)",p.tenantId(),p.id(),actor,action,type,id,version,hash(JSON.writeValueAsString(snapshot)));
        jdbc.update("INSERT INTO core_outbox_event(tenant_id,project_id,field_id,link_id,object_revision,event_type,payload) VALUES(?,?,?,?,?,?,?::jsonb)",p.tenantId(),p.id(),type.equals("field_definition")?id:null,type.equals("trace_link")?id:null,version,action,JSON.writeValueAsString(snapshot));
    }
    public static long version(String tag) {
        if(tag==null || tag.isBlank() || tag.trim().equals("*")) throw ProjectAccessException.preconditionRequired("A specific If-Match is required");
        if(!tag.matches("\"[1-9][0-9]*\"")) throw ProjectAccessException.invalid("If-Match requires one strong numeric tag");
        try{return Long.parseLong(tag.substring(1,tag.length()-1));}catch(NumberFormatException ex){throw ProjectAccessException.invalid("If-Match overflows");}
    }
    public static int limit(Integer n) {if(n==null)return 25; if(n<1||n>100)throw ProjectAccessException.invalid("limit must be 1..100");return n;}
    public static UUID cursor(String s) {if(s==null||s.isBlank())return new UUID(0,0);try{return UUID.fromString(s);}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("Invalid cursor");}}
    public static String canonical(JsonNode n) {
        if(n.isObject()) {var names=new java.util.ArrayList<String>();n.propertyNames().forEach(names::add);names.sort(String::compareTo);
            StringBuilder b=new StringBuilder("O");for(String k:names)b.append(k.length()).append(':').append(k).append(canonical(n.get(k)));return b.append(';').toString();}
        if(n.isArray()){StringBuilder b=new StringBuilder("A");for(JsonNode v:n)b.append(canonical(v));return b.append(';').toString();}
        String s=n.toString();return "V"+s.length()+":"+s+";";
    }
    public static String hash(String... values) {
        try {StringBuilder b=new StringBuilder();for(String s:values)b.append(s==null?"N;":"V"+s.length()+":"+s+";");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public record Page<T>(java.util.List<T> items,String nextCursor) { }
}
