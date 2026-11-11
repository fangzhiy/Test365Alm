package com.test365alm.server.signature;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;
import tools.jackson.databind.JsonNode;

@Service
public class SignatureService {
    private final JdbcTemplate jdbc;
    private final CoreCommands commands;
    private final SignatureProvider provider;
    public SignatureService(JdbcTemplate jdbc,CoreCommands commands,SignatureProvider provider) {
        this.jdbc=jdbc;this.commands=commands;this.provider=provider;
    }

    @Transactional(readOnly=true)
    public View get(UUID actor,UUID project,SignatureRules.Kind kind,UUID object,UUID snapshot) {
        var p=commands.scope(actor,project,false,false);
        var target=target(p,actor,kind,object,snapshot,false);
        return view(p,kind,object,snapshot,target);
    }

    @Transactional
    public Challenge prepare(UUID actor,UUID project,SignatureRules.Kind kind,UUID object,UUID snapshot,
            JsonNode body,String tag,String key,String sessionBinding) {
        SignatureRules.empty(body);long expected=CoreCommands.version(tag);
        commands.lock(project);var p=commands.scope(actor,project,true,false);lockActor(p,actor);
        var target=target(p,actor,kind,object,snapshot,true);
        requireIdentity(p,actor,kind,target);
        String route=route("challenge",kind,object,snapshot),hash=CoreCommands.hash(Long.toString(expected),sessionBinding);
        var replay=commands.replay(p,actor,route,key,hash,Challenge.class);if(replay!=null)return replay;
        requireSigner(target);
        checkVersion(target,expected);
        if(record(p,kind,object,snapshot)!=null)throw ProjectAccessException.conflict("ALREADY_SIGNED","This exact snapshot is already signed");
        var identity=identity(actor);UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO signature_challenge(id,tenant_id,project_id,kind,object_id,snapshot_id,source_version,
             actor_id,issuer,subject,display_name,session_binding,content_snapshot,content_digest)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
            """,id,p.tenantId(),project,kind.name(),object,snapshot,expected,actor,identity.issuer(),identity.subject(),
            identity.displayName(),sessionBinding,target.content().toString(),target.digest());
        return saveChallenge(p,actor,route,key,hash,challenge(p,id));
    }

    @Transactional
    public SignatureLoginFlow.Pending authenticate(UUID actor,UUID project,SignatureRules.Kind kind,UUID object,UUID snapshot,
            UUID challengeId,String sessionBinding) {
        commands.lock(project);var p=commands.scope(actor,project,true,false);lockActor(p,actor);
        var target=target(p,actor,kind,object,snapshot,true);requireIdentity(p,actor,kind,target);requireSigner(target);
        var c=challenge(p,challengeId);validateChallenge(c,actor,kind,object,snapshot,sessionBinding,target);
        if(record(p,kind,object,snapshot)!=null)throw ProjectAccessException.conflict("ALREADY_SIGNED","This exact snapshot is already signed");
        return new SignatureLoginFlow.Pending(c.id(),project,actor,c.issuer(),c.subject(),c.createdAt().toInstant(),null);
    }

    @Transactional
    public View sign(UUID actor,UUID project,SignatureRules.Kind kind,UUID object,UUID snapshot,JsonNode body,String tag,String key,
            String sessionBinding,SignatureLoginFlow.Proof proof) {
        UUID challengeId=SignatureRules.challenge(body);long expected=CoreCommands.version(tag);
        commands.lock(project);var p=commands.scope(actor,project,true,false);lockActor(p,actor);
        var target=target(p,actor,kind,object,snapshot,true);
        // Replays authorize the historical designated identity too, but do not reinterpret its original state/version.
        requireIdentity(p,actor,kind,target);
        String route=route("sign",kind,object,snapshot),hash=CoreCommands.hash(Long.toString(expected),challengeId.toString());
        var replay=commands.replay(p,actor,route,key,hash,View.class);if(replay!=null)return replay;
        requireSigner(target);checkVersion(target,expected);
        if(record(p,kind,object,snapshot)!=null)throw ProjectAccessException.conflict("ALREADY_SIGNED","This exact snapshot is already signed");
        var c=challenge(p,challengeId);validateChallenge(c,actor,kind,object,snapshot,sessionBinding,target);
        if(proof==null || !proof.challengeId().equals(c.id()) || !proof.actorId().equals(actor))throw ProjectAccessException.forbidden();
        var now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",OffsetDateTime.class);
        SignatureRules.reauthenticated(c.issuer(),c.subject(),proof.issuer(),proof.subject(),proof.authenticatedAt(),c.createdAt().toInstant(),now.toInstant());
        UUID id=UUID.randomUUID();
        var evidence=provider.sign(new SignatureProvider.Material(id,project,kind,object,snapshot,c.digest(),actor,c.issuer(),c.subject(),
            c.displayName(),proof.authenticatedAt(),now.toInstant()));
        var signed=new SignedRecord(id,"SIGNATURE_RECORD_V1",project,kind,kind.meaning(),object,snapshot,expected,actor,c.issuer(),c.subject(),
            c.displayName(),OffsetDateTime.ofInstant(proof.authenticatedAt(),java.time.ZoneOffset.UTC),now,c.digest(),c.content(),evidence);
        String json=CoreCommands.JSON.writeValueAsString(signed);
        jdbc.update("""
            INSERT INTO signature_record(id,tenant_id,project_id,challenge_id,kind,object_id,snapshot_id,actor_id,content_digest,payload)
            VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)
            """,id,p.tenantId(),project,c.id(),kind.name(),object,snapshot,actor,c.digest(),json);
        jdbc.update("INSERT INTO audit_event(id,tenant_id,project_id,actor_principal_id,action,object_type,object_id,object_revision,after_hash) VALUES(?,?,?,?,'signature.signed','signature',?,?,?)",
            id,p.tenantId(),project,actor,id,expected,c.digest());
        jdbc.update("INSERT INTO signature_outbox_event(id,tenant_id,project_id,event_type,payload) VALUES(?,?,?,'signature.signed',?::jsonb)",id,p.tenantId(),project,json);
        var result=new View(kind,object,snapshot,target.version(),target.state(),false,target.digest(),signed);
        commands.save(p,actor,route,key,hash,result);
        return result;
    }

    private Target target(ProjectService.ProjectView p,UUID actor,SignatureRules.Kind kind,UUID object,UUID snapshot,boolean lock) {
        String sql=switch(kind) {
            case TEST_APPROVAL -> """
                SELECT c.row_version,c.current_revision_id,COALESCE((SELECT e.state FROM test_review_event e WHERE e.tenant_id=c.tenant_id
                 AND e.project_id=c.project_id AND e.test_case_id=c.id AND e.revision_id=r.id ORDER BY e.row_version DESC LIMIT 1),'DRAFT') AS state,
                 NULLIF(r.business_fields#>>'{reviewer,value}','')::uuid AS reviewer
                FROM test_case c JOIN test_revision r ON r.tenant_id=c.tenant_id AND r.project_id=c.project_id AND r.test_case_id=c.id
                WHERE c.tenant_id=? AND c.project_id=? AND c.id=? AND r.id=? AND r.sealed_at IS NOT NULL
                """+(lock?" FOR UPDATE OF c":"");
            case RUN_COMPLETION -> "SELECT row_version,id AS current_revision_id,status AS state,NULL::uuid AS reviewer FROM run_attempt WHERE tenant_id=? AND project_id=? AND run_id=? AND id=?"+(lock?" FOR UPDATE":"");
            case DEVIATION_CLOSURE -> "SELECT d.row_version,d.current_revision_id,r.status AS state,NULL::uuid AS reviewer FROM defect d JOIN defect_revision r ON r.tenant_id=d.tenant_id AND r.project_id=d.project_id AND r.defect_id=d.id WHERE d.tenant_id=? AND d.project_id=? AND d.id=? AND r.id=?"+(lock?" FOR UPDATE OF d":"");
        };
        var rows=jdbc.query(sql,(rs,i)->new Header(rs.getLong("row_version"),rs.getObject("current_revision_id",UUID.class),
            rs.getString("state"),rs.getObject("reviewer",UUID.class)),p.tenantId(),p.id(),object,snapshot);
        if(rows.isEmpty())throw ProjectAccessException.notFound();var h=rows.get(0);
        var contents=jdbc.query("""
            SELECT content::text,encode(sha256(convert_to(content::text,'UTF8')),'hex') AS digest
            FROM (SELECT app_signature_snapshot(?,?,?,?,?) AS content) s
            """,(rs,i)->new Content(CoreCommands.JSON.readTree(rs.getString(1)),rs.getString(2)),p.tenantId(),p.id(),kind.name(),object,snapshot);
        var content=contents.get(0);
        boolean signable=Boolean.TRUE.equals(jdbc.queryForObject("SELECT app_signature_version(?,?,?,?,?,?) IS NOT NULL",Boolean.class,p.tenantId(),p.id(),kind.name(),object,snapshot,actor));
        return new Target(h.version(),h.current().equals(snapshot),h.state(),h.reviewer(),content.value(),content.digest(),signable);
    }
    private View view(ProjectService.ProjectView p,SignatureRules.Kind kind,UUID object,UUID snapshot,Target target) {
        var signed=record(p,kind,object,snapshot);
        return new View(kind,object,snapshot,target.version(),target.state(),signed==null && target.signable(),signed==null?target.digest():signed.contentDigest(),signed);
    }
    private SignedRecord record(ProjectService.ProjectView p,SignatureRules.Kind kind,UUID object,UUID snapshot) {
        var rows=jdbc.query("SELECT payload::text FROM signature_record WHERE tenant_id=? AND project_id=? AND kind=? AND object_id=? AND snapshot_id=?",
            (rs,i)->CoreCommands.JSON.readValue(rs.getString(1),SignedRecord.class),p.tenantId(),p.id(),kind.name(),object,snapshot);
        return rows.isEmpty()?null:rows.get(0);
    }
    private StoredChallenge challenge(ProjectService.ProjectView p,UUID id) {
        var rows=jdbc.query("SELECT * FROM signature_challenge WHERE tenant_id=? AND project_id=? AND id=?",(rs,i)->new StoredChallenge(id,
            SignatureRules.kind(rs.getString("kind")),rs.getObject("object_id",UUID.class),rs.getObject("snapshot_id",UUID.class),rs.getLong("source_version"),
            rs.getObject("actor_id",UUID.class),rs.getString("issuer"),rs.getString("subject"),rs.getString("display_name"),rs.getString("session_binding"),
            CoreCommands.JSON.readTree(rs.getString("content_snapshot")),rs.getString("content_digest"),rs.getObject("created_at",OffsetDateTime.class)),p.tenantId(),p.id(),id);
        if(rows.isEmpty())throw ProjectAccessException.notFound();return rows.get(0);
    }
    private Challenge saveChallenge(ProjectService.ProjectView p,UUID actor,String route,String key,String hash,StoredChallenge c) {
        var response=new Challenge(c.id(),c.createdAt().plusMinutes(5),"/api/v1/projects/"+p.id()+"/signatures/"+c.kind()+"/"+c.objectId()+"/"+c.snapshotId()+"/challenges/"+c.id()+"/authenticate");
        commands.save(p,actor,route,key,hash,response);return response;
    }
    private void validateChallenge(StoredChallenge c,UUID actor,SignatureRules.Kind kind,UUID object,UUID snapshot,String binding,Target target) {
        if(!c.actorId().equals(actor) || c.kind()!=kind || !c.objectId().equals(object) || !c.snapshotId().equals(snapshot)
            || !c.binding().equals(binding) || !OffsetDateTime.now().isBefore(c.createdAt().plusMinutes(5)))throw ProjectAccessException.forbidden();
        checkVersion(target,c.version());
        if(!c.digest().equals(target.digest()) || !CoreCommands.canonical(c.content()).equals(CoreCommands.canonical(target.content())))
            throw ProjectAccessException.conflict("SIGNATURE_CONTENT_CHANGED","The frozen signing content changed; start a new challenge");
    }
    private void requireIdentity(ProjectService.ProjectView p,UUID actor,SignatureRules.Kind kind,Target target) {
        if(kind==SignatureRules.Kind.TEST_APPROVAL && (!actor.equals(target.reviewer()) || !Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT app_has_active_project_writer(?,?,?)",Boolean.class,p.tenantId(),p.id(),target.reviewer()))))throw ProjectAccessException.forbidden();
    }
    private static void requireSigner(Target target) {
        if(!target.signable())throw ProjectAccessException.conflict("SIGNATURE_NOT_ALLOWED","The current identity or exact snapshot cannot sign");
    }
    private static void checkVersion(Target target,long expected) {
        if(target.version()!=expected)throw ProjectAccessException.preconditionFailed("STALE_VERSION","The signing target changed; read the current version");
    }
    private void lockActor(ProjectService.ProjectView p,UUID actor) {
        var ids=jdbc.query("SELECT id FROM app_execution_people(?,?,?,?,?,true)",(rs,i)->rs.getObject(1,UUID.class),p.tenantId(),p.id(),actor,new UUID(0,0),1);
        if(ids.size()!=1 || !ids.get(0).equals(actor))throw ProjectAccessException.forbidden();
    }
    private Identity identity(UUID actor) {
        return jdbc.queryForObject("SELECT issuer,subject,display_name FROM principal WHERE id=? AND disabled_at IS NULL",
            (rs,i)->new Identity(rs.getString(1),rs.getString(2),rs.getString(3)),actor);
    }
    private static String route(String action,SignatureRules.Kind kind,UUID object,UUID snapshot) {return "signature:"+action+":"+kind+":"+object+":"+snapshot;}
    private record Identity(String issuer,String subject,String displayName) { }
    private record Header(long version,UUID current,String state,UUID reviewer) { }
    private record Content(JsonNode value,String digest) { }
    private record Target(long version,boolean current,String state,UUID reviewer,JsonNode content,String digest,boolean signable) { }
    private record StoredChallenge(UUID id,SignatureRules.Kind kind,UUID objectId,UUID snapshotId,long version,UUID actorId,String issuer,
        String subject,String displayName,String binding,JsonNode content,String digest,OffsetDateTime createdAt) { }
    public record Challenge(UUID id,OffsetDateTime expiresAt,String authenticationUrl) { }
    public record SignedRecord(UUID id,String schema,UUID projectId,SignatureRules.Kind kind,String meaning,UUID objectId,UUID snapshotId,long sourceVersion,
        UUID actorId,String issuer,String subject,String displayName,OffsetDateTime authenticatedAt,OffsetDateTime signedAt,String contentDigest,
        JsonNode contentSnapshot,SignatureProvider.Evidence evidence) { }
    public record View(SignatureRules.Kind kind,UUID objectId,UUID snapshotId,long rowVersion,String state,boolean canSign,String contentDigest,SignedRecord signature) { }
}
