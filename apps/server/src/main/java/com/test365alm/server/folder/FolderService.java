package com.test365alm.server.folder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import tools.jackson.databind.ObjectMapper;

@Service
public class FolderService {
    private final JdbcTemplate jdbc;
    private final ProjectService projects;
    private final ObjectMapper json = new ObjectMapper();

    public FolderService(JdbcTemplate jdbc, ProjectService projects) { this.jdbc=jdbc; this.projects=projects; }

    @Transactional(readOnly=true)
    public List<Folder> tree(UUID actor, UUID project, String kind) {
        scope(actor,project,kind,false);
        return jdbc.query("SELECT * FROM project_folder WHERE project_id=? AND kind=? ORDER BY sort_order,name,id",
                FolderService::folder,project,kind);
    }

    @Transactional
    public Folder create(UUID actor, UUID project, String kind, UUID parent, String name, int order, String key) {
        UUID tenant=scope(actor,project,kind,true);
        String safe=name(name); order(order);
        if (key==null || !key.matches("[A-Za-z0-9._:-]{8,200}")) throw ProjectAccessException.invalid("Idempotency-Key must contain 8..200 safe characters");
        String hash=encode(List.of(kind,Objects.toString(parent,""),safe,order));
        var replay=jdbc.query("SELECT request_hash,response_json,expires_at>CURRENT_TIMESTAMP AS valid FROM folder_idempotency WHERE tenant_id=? AND project_id=? AND principal_id=? AND idempotency_key=?",
                (rs,n)->new Replay(rs.getString(1),rs.getString(2),rs.getBoolean(3)),tenant,project,actor,key);
        if (!replay.isEmpty()) {
            Replay prior=replay.get(0);
            if (!prior.valid() || !prior.hash().equals(hash)) throw ProjectAccessException.conflict("IDEMPOTENCY_CONFLICT","Use a new key for a new intent");
            return json.readValue(prior.response(),Folder.class);
        }
        parent(project,kind,parent); duplicate(project,kind,parent,safe,null);
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO project_folder (tenant_id,project_id,id,kind,parent_id,name,sort_order) VALUES (?,?,?,?,?,?,?)",tenant,project,id,kind,parent,safe,order);
        Folder result=get(project,id);
        event(tenant,project,actor,id,1,"folder.created",result);
        jdbc.update("INSERT INTO folder_idempotency (tenant_id,project_id,principal_id,idempotency_key,request_hash,response_json) VALUES (?,?,?,?,?,?)",tenant,project,actor,key,hash,encode(result));
        return result;
    }

    @Transactional
    public Folder update(UUID actor, UUID project, UUID id, UUID parent, String name, int order, String match) {
        UUID tenant=scope(actor,project,null,true);
        Folder old=get(project,id); check(old.rowVersion(),match);
        String safe=name(name); order(order); parent(project,old.kind(),parent);
        if (id.equals(parent) || (parent!=null && Boolean.TRUE.equals(jdbc.queryForObject("""
            WITH RECURSIVE descendants AS (
                SELECT id FROM project_folder WHERE project_id=? AND parent_id=?
                UNION SELECT f.id FROM project_folder f JOIN descendants d ON f.parent_id=d.id
            ) SELECT EXISTS(SELECT 1 FROM descendants WHERE id=?)
            """,Boolean.class,project,id,parent)))) throw ProjectAccessException.conflict("FOLDER_CYCLE","A folder cannot be moved beneath itself");
        duplicate(project,old.kind(),parent,safe,id);
        jdbc.update("UPDATE project_folder SET parent_id=?,name=?,sort_order=?,row_version=row_version+1 WHERE project_id=? AND id=?",parent,safe,order,project,id);
        Folder result=get(project,id); event(tenant,project,actor,id,result.rowVersion(),"folder.updated",result); return result;
    }

    @Transactional
    public void delete(UUID actor, UUID project, UUID id, String match) {
        UUID tenant=scope(actor,project,null,true); Folder old=get(project,id); check(old.rowVersion(),match);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM project_folder WHERE parent_id=?) OR EXISTS(SELECT 1 FROM folder_placement WHERE folder_id=?)",Boolean.class,id,id)))
            throw ProjectAccessException.conflict("FOLDER_NOT_EMPTY","Move child folders and objects before deleting");
        event(tenant,project,actor,id,old.rowVersion(),"folder.deleted",old);
        jdbc.update("DELETE FROM project_folder WHERE project_id=? AND id=?",project,id);
    }

    @Transactional(readOnly=true)
    public Placement location(UUID actor, UUID project, String kind, UUID object) {
        scope(actor,project,kind,false); object(project,kind,object); return placement(project,kind,object);
    }

    @Transactional
    public Placement place(UUID actor, UUID project, String kind, UUID object, UUID target, String match) {
        UUID tenant=scope(actor,project,kind,true); object(project,kind,object); parent(project,kind,target);
        Placement old=placement(project,kind,object); check(old.rowVersion(),match);
        jdbc.update("""
            INSERT INTO folder_placement (tenant_id,project_id,kind,object_id,test_case_id,test_set_id,folder_id,row_version)
            VALUES (?,?,?,?,?,?,?,2) ON CONFLICT (tenant_id,project_id,kind,object_id)
            DO UPDATE SET folder_id=EXCLUDED.folder_id,row_version=folder_placement.row_version+1
            """,tenant,project,kind,object,kind.equals("TEST_PLAN")?object:null,kind.equals("TEST_LAB")?object:null,target);
        Placement result=placement(project,kind,object); event(tenant,project,actor,object,result.rowVersion(),"folder.placed",result); return result;
    }

    private UUID scope(UUID actor, UUID project, String kind, boolean write) {
        if (kind!=null) kind(kind);
        if (write) jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,1))",rs->{},project.toString());
        var p=write?projects.requireTestWriteAccess(actor,project):projects.requireTestReadAccess(actor,project);
        jdbc.queryForObject("SELECT set_config('test365alm.tenant_id',?,true)",String.class,p.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('test365alm.project_id',?,true)",String.class,project.toString());
        jdbc.queryForObject("SELECT set_config('test365alm.principal_id',?,true)",String.class,actor.toString());
        return p.tenantId();
    }
    private Folder get(UUID project, UUID id) {
        var rows=jdbc.query("SELECT * FROM project_folder WHERE project_id=? AND id=?",FolderService::folder,project,id);
        if (rows.isEmpty()) throw ProjectAccessException.notFound(); return rows.get(0);
    }
    private void parent(UUID project, String kind, UUID id) {
        if (id!=null && !get(project,id).kind().equals(kind)) throw ProjectAccessException.invalid("Folder belongs to another tree");
    }
    private void object(UUID project,String kind,UUID id) {
        String table=kind.equals("TEST_PLAN")?"test_case":"test_set";
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE project_id=? AND id=?)",Boolean.class,project,id))) throw ProjectAccessException.notFound();
    }
    private Placement placement(UUID project,String kind,UUID object) {
        var rows=jdbc.query("SELECT folder_id,row_version FROM folder_placement WHERE project_id=? AND kind=? AND object_id=?",
                (rs,n)->new Placement(object,rs.getObject(1,UUID.class),rs.getLong(2)),project,kind,object);
        return rows.isEmpty()?new Placement(object,null,1):rows.get(0);
    }
    private void duplicate(UUID project,String kind,UUID parent,String name,UUID except) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM project_folder WHERE project_id=? AND kind=? AND parent_id IS NOT DISTINCT FROM ?::uuid AND name=? AND id IS DISTINCT FROM ?::uuid)",Boolean.class,project,kind,parent,name,except)))
            throw ProjectAccessException.conflict("FOLDER_NAME_EXISTS","A sibling folder already has this name");
    }
    private void event(UUID tenant,UUID project,UUID actor,UUID object,long version,String action,Object payload) {
        jdbc.update("INSERT INTO folder_outbox_event (tenant_id,project_id,actor_principal_id,object_id,object_version,event_type,payload) VALUES (?,?,?,?,?,?,?)",tenant,project,actor,object,version,action,encode(payload));
        jdbc.update("INSERT INTO audit_event (tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision) VALUES (?,?,?,?,'organization',?,?)",tenant,project,actor,action,object,version);
    }
    static void kind(String kind) { if (!List.of("TEST_PLAN","TEST_LAB").contains(kind)) throw ProjectAccessException.invalid("Invalid folder kind"); }
    public static void filter(String value) {
        if (value==null || value.equals("root")) return;
        try { UUID.fromString(value); } catch (IllegalArgumentException e) { throw ProjectAccessException.invalid("folderId must be root or a UUID"); }
    }
    static String name(String name) { if (name==null || name.trim().isEmpty() || name.trim().length()>200) throw ProjectAccessException.invalid("Folder name must contain 1..200 characters"); return name.trim(); }
    static void order(int order) { if (order<0) throw ProjectAccessException.invalid("sortOrder must be nonnegative"); }
    static long version(String match) {
        if (match==null) throw ProjectAccessException.preconditionRequired("If-Match is required");
        if (!match.matches("\"[1-9][0-9]*\"")) throw ProjectAccessException.invalid("A strong positive If-Match is required");
        try { return Long.parseLong(match.substring(1,match.length()-1)); } catch (NumberFormatException e) { throw ProjectAccessException.invalid("Invalid If-Match"); }
    }
    private static void check(long actual,String match) { if (actual!=version(match)) throw ProjectAccessException.preconditionFailed("STALE_VERSION","Refresh before retrying this change"); }
    private String encode(Object value) { return json.writeValueAsString(value); }
    private static Folder folder(ResultSet rs,int n) throws SQLException { return new Folder(rs.getObject("id",UUID.class),rs.getString("kind"),rs.getObject("parent_id",UUID.class),rs.getString("name"),rs.getInt("sort_order"),rs.getLong("row_version")); }
    public record Folder(UUID id,String kind,UUID parentId,String name,int sortOrder,long rowVersion) {}
    public record Placement(UUID objectId,UUID folderId,long rowVersion) {}
    private record Replay(String hash,String response,boolean valid) {}
}
