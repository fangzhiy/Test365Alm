package com.test365alm.server.report;

import java.util.*;
import org.springframework.http.HttpStatus;
import com.test365alm.server.project.ProjectAccessException;

public final class ReportRules {
    private ReportRules() { }
    public static final List<String> STATES=List.of("NOT_RUN","RUNNING","PAUSED","PASS","FAIL","BLOCKED");
    public record Counts(long matched, Map<String,Long> categories, Double completionRate, Double passRate) { }
    public static Counts counts(List<String> states) {
        Map<String,Long> values=new LinkedHashMap<>(); STATES.forEach(s->values.put(s,0L));
        for(String state:states) {
            if(!values.containsKey(state)) throw new IllegalStateException("Unsupported stored execution state");
            values.put(state,values.get(state)+1);
        }
        long finished=values.get("PASS")+values.get("FAIL")+values.get("BLOCKED");
        return new Counts(states.size(),Map.copyOf(values),states.isEmpty()?null:(double)finished/states.size(),
                finished==0?null:(double)values.get("PASS")/finished);
    }
    public static String filter(String value,List<String> allowed) {
        if(value==null||value.isEmpty())return null;
        if(!allowed.contains(value))throw ProjectAccessException.invalid("Unsupported report filter");return value;
    }
    public static void limit(long size,long max) {
        if(size>max)throw new ProjectAccessException("REPORT_LIMIT_EXCEEDED",HttpStatus.UNPROCESSABLE_ENTITY,
                "Report limit exceeded; narrow filters or select a smaller test set");
    }
}
