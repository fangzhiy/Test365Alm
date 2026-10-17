package com.test365alm.server.release;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessException;

/** Shared strict business-date and finite lifecycle validation; no coercion. */
public final class ReleaseRules {
    private ReleaseRules() { }
    public record Content(String name,String description,LocalDate startDate,LocalDate endDate,String status) { }
    public static Content release(JsonNode body,Content old) {return content(body,old,false);}
    public static Content cycle(JsonNode body,Content old) {return content(body,old,true);}
    private static Content content(JsonNode body,Content old,boolean cycle) {
        FieldRules.fields(body,cycle?(old==null?Set.of("name","startDate","endDate"):Set.of("name","startDate","endDate","status"))
            :(old==null?Set.of("name","description","startDate","endDate"):Set.of("name","description","startDate","endDate","status")));
        if(old!=null && body.isEmpty())throw ProjectAccessException.invalid("Patch must contain a field");
        String name=body.has("name")?FieldRules.text(body.get("name")):old==null?FieldRules.text(null):old.name();
        if(name.isBlank() || name.codePointCount(0,name.length())>200)throw ProjectAccessException.unprocessable("Name must contain 1..200 characters");
        String description=body.has("description")?FieldRules.text(body.get("description")):old==null?"":old.description();
        if(description.codePointCount(0,description.length())>10000)throw ProjectAccessException.unprocessable("Description exceeds 10000 characters");
        LocalDate start=body.has("startDate")?date(body.get("startDate")):old==null?date(null):old.startDate();
        LocalDate end=body.has("endDate")?date(body.get("endDate")):old==null?date(null):old.endDate();
        if(start.isAfter(end))throw ProjectAccessException.unprocessable("startDate must not exceed endDate");
        String status=body.has("status")?FieldRules.text(body.get("status")):old==null?"PLANNED":old.status();
        if(!(cycle?Set.of("PLANNED","ACTIVE","CLOSED"):Set.of("PLANNED","ACTIVE","CLOSED","ARCHIVED")).contains(status))throw ProjectAccessException.invalid("Unsupported status");
        if(old!=null) {
            if(!old.status().equals(status) && !Set.of("PLANNED:ACTIVE","PLANNED:CLOSED","PLANNED:ARCHIVED","ACTIVE:CLOSED","ACTIVE:ARCHIVED","CLOSED:ARCHIVED").contains(old.status()+":"+status))
                throw ProjectAccessException.conflict("INVALID_TRANSITION","Lifecycle cannot move backwards");
            boolean onlyArchive=!cycle && old.status().equals("CLOSED") && status.equals("ARCHIVED")
                && name.trim().equals(old.name()) && description.equals(old.description())
                && start.equals(old.startDate()) && end.equals(old.endDate());
            if((old.status().equals("CLOSED") || old.status().equals("ARCHIVED")) && !onlyArchive)
                throw ProjectAccessException.conflict(cycle?"CYCLE_READ_ONLY":"RELEASE_READ_ONLY","Closed or archived content is read-only");
        }
        return new Content(name.trim(),description,start,end,status);
    }
    private static LocalDate date(JsonNode value) {
        String text=FieldRules.text(value);
        if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw ProjectAccessException.unprocessable("Date must be YYYY-MM-DD");
        try {var date=LocalDate.parse(text);if(date.getYear()<1)throw new DateTimeParseException("Year",text,0);return date;}
        catch(DateTimeParseException ex){throw ProjectAccessException.unprocessable("Date must be a real business date");}
    }
}
