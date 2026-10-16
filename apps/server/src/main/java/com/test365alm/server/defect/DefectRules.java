package com.test365alm.server.defect;

import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessException;

public final class DefectRules {
    private DefectRules() { }
    private static final Set<String> LEVELS=Set.of("LOW","MEDIUM","HIGH","CRITICAL");
    private static final Set<String> EDGES=Set.of("OPEN:IN_PROGRESS","OPEN:RESOLVED","IN_PROGRESS:RESOLVED",
        "RESOLVED:CLOSED","RESOLVED:REOPENED","REOPENED:IN_PROGRESS","REOPENED:RESOLVED");
    public record Content(String title,String description,String severity,String priority) { }
    public static Content content(JsonNode n) {
        FieldRules.fields(n,Set.of("title","description","severity","priority"));
        String title=text(n,"title",500,true),description=text(n,"description",100000,false);
        String severity=text(n,"severity",20,true),priority=text(n,"priority",20,true);
        if(!LEVELS.contains(severity)||!LEVELS.contains(priority))throw ProjectAccessException.invalid("Unsupported severity or priority");
        return new Content(title,description,severity,priority);
    }
    public static String text(JsonNode n,String key,int max,boolean required) {
        String value=FieldRules.text(n.get(key));
        if(value.length()>max || (required&&value.isBlank()))throw ProjectAccessException.unprocessable("Invalid "+key+" length");
        return required?value.trim():value;
    }
    public static void transition(String from,String to,String reason) {
        if(!EDGES.contains(from+":"+to))throw ProjectAccessException.conflict("INVALID_TRANSITION","Status transition is not allowed");
        if(reason==null || reason.length()>10000 || (to.equals("RESOLVED")&&reason.isBlank()))
            throw ProjectAccessException.unprocessable("Resolution requires a repair explanation (max 10000)");
    }
}
