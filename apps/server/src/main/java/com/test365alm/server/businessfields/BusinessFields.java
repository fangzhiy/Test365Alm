package com.test365alm.server.businessfields;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.core.CoreCommands;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessException;
import com.test365alm.server.project.ProjectService;

/** Frozen values travel with the content revision, never the stable identity. */
public final class BusinessFields {
    private BusinessFields() { }
    public static final Map<String,Set<String>> KEYS=Map.of(
        "test_case",Set.of("test_purpose","reviewer"),
        "defect",Set.of("occurrence_department","occurrence_location","reviewer"),
        "requirement",Set.of("equipment_system_id","owning_department"));
    public static JsonNode empty(){return CoreCommands.JSON.createObjectNode();}
    public static JsonNode read(String json){return json==null?empty():CoreCommands.JSON.readTree(json);}
    public static String hash(JsonNode n){return n==null?null:CoreCommands.canonical(n);}
    private static UUID uuid(String text) {
        UUID value=UUID.fromString(text);
        if(!value.toString().equalsIgnoreCase(text))throw new IllegalArgumentException("Canonical UUID required");
        return value;
    }
    public static JsonNode normalize(String kind,JsonNode supplied,JdbcTemplate jdbc,ProjectService.ProjectView p) {
        if(supplied==null)return empty();
        FieldRules.fields(supplied,KEYS.get(kind));
        var result=CoreCommands.JSON.createObjectNode();
        for(String key:supplied.propertyNames()) {
            var entry=supplied.get(key);
            FieldRules.fields(entry,Set.of("fieldId","revision","value","kind","definition"));
            UUID field=null;long revision=1;JsonNode definition;
            if(entry.hasNonNull("fieldId")) {
                try{field=uuid(FieldRules.text(entry.get("fieldId")));}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("Invalid fieldId");}
                var v=entry.get("revision");
                if(v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<1)throw ProjectAccessException.invalid("Positive field revision required");
                revision=v.longValue();
                if(jdbc==null||p==null)throw ProjectAccessException.notFound();
                var rows=jdbc.query("SELECT r.definition::text FROM field_definition f JOIN field_definition_revision r ON r.tenant_id=f.tenant_id AND r.project_id=f.project_id AND r.field_id=f.id WHERE f.tenant_id=? AND f.project_id=? AND f.id=? AND f.field_key=? AND r.revision_no=?",(rs,i)->read(rs.getString(1)),p.tenantId(),p.id(),field,key,revision);
                if(rows.isEmpty())throw ProjectAccessException.notFound();
                definition=rows.get(0);
            } else {
                if(entry.has("revision")&&(!entry.get("revision").isIntegralNumber()||entry.get("revision").longValue()!=1))throw ProjectAccessException.invalid("Unknown system field revision");
                definition=CoreCommands.JSON.createObjectNode().put("key",key).put("type","TEXT").put("required",false).put("maxLength",key.equals("test_purpose")?4000:key.equals("reviewer")?36:200);
            }
            if(entry.has("definition")&&!entry.get("definition").equals(definition))throw ProjectAccessException.invalid("Definition does not match its frozen revision");
            if(entry.has("kind")&&!FieldRules.text(entry.get("kind")).equals(field==null?"SYSTEM":"CUSTOM"))throw ProjectAccessException.invalid("Invalid field kind");
            var value=FieldRules.value(definition,entry.get("value"));
            if(key.equals("reviewer")&&!value.isNull()&&!value.asText().isBlank()) {
                UUID reviewer;
                try{reviewer=uuid(value.asText());}catch(IllegalArgumentException ex){throw ProjectAccessException.unprocessable("Reviewer must be a local principal UUID");}
                if(jdbc==null||p==null||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT app_has_active_project_member(?,?,?)",Boolean.class,p.tenantId(),p.id(),reviewer)))throw ProjectAccessException.unprocessable("Reviewer must be an active project member");
            }
            var frozen=CoreCommands.JSON.createObjectNode().put("kind",field==null?"SYSTEM":"CUSTOM").put("revision",revision);
            if(field!=null)frozen.put("fieldId",field.toString());
            frozen.set("definition",definition);frozen.set("value",value);result.set(key,frozen);
        }
        return result;
    }
}
