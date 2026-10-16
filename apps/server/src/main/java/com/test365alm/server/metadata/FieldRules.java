package com.test365alm.server.metadata;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.project.ProjectAccessException;

/** One validator for definition defaults, HTTP and the workbench. No float coercion. */
public final class FieldRules {
    private FieldRules() { }
    public static final Set<String> RESERVED = Set.of("id", "title", "body", "priority", "tenant_id", "project_id",
        "created_by", "created_at", "row_version", "current_revision_id", "test_type", "steps", "status");
    public static JsonNode definition(JsonNode d) {
        fields(d, Set.of("key","type","required","maxLength","precision","scale","minimum","maximum","options","defaultValue"));
        String key = text(d.get("key"));
        if (!key.matches("[a-z][a-z0-9_]{0,63}") || RESERVED.contains(key)) invalid("Reserved or invalid field key");
        String type = text(d.get("type"));
        if (d.get("required") == null || !d.get("required").isBoolean()) invalid("required must be boolean");
        Set<String> permitted;
        switch (type) {
            case "TEXT" -> { integer(d.get("maxLength"), 1, 100000); permitted=Set.of("maxLength"); }
            case "NUMBER" -> {
                int precision=integer(d.get("precision"),1,28);
                integer(d.get("scale"),0,precision);
                for(String bound:Set.of("minimum","maximum")) if(d.has(bound)) decimal(d.get(bound));
                if(d.has("minimum") && d.has("maximum") && decimal(d.get("minimum")).compareTo(decimal(d.get("maximum")))>0)
                    invalid("minimum exceeds maximum");
                permitted=Set.of("precision","scale","minimum","maximum");
            }
            case "ENUM" -> {
                JsonNode options=d.get("options");
                if(options==null || !options.isArray() || options.size()<1 || options.size()>100) invalid("options must contain 1..100 values");
                Set<String> unique=new HashSet<>();
                for(JsonNode option:options) {String s=text(option); if(s.isBlank() || s.length()>100 || !unique.add(s)) invalid("Invalid or duplicate option");}
                permitted=Set.of("options");
            }
            default -> throw ProjectAccessException.invalid("Unsupported field type");
        }
        for(String name:d.propertyNames())
            if(!Set.of("key","type","required","defaultValue").contains(name) && !permitted.contains(name)) invalid("Constraint does not apply to type");
        if(d.has("defaultValue")) value(d,d.get("defaultValue"));
        return d;
    }
    public static JsonNode value(JsonNode d, JsonNode supplied) {
        JsonNode v=supplied;
        if(v==null || v.isNull()) v=d.get("defaultValue");
        if(v==null || v.isNull()) {
            if(d.get("required").booleanValue()) reject("Value is required");
            return tools.jackson.databind.node.NullNode.getInstance();
        }
        if(!v.isTextual()) reject("Value must be a string");
        String s=v.textValue();
        if(d.get("required").booleanValue() && s.isBlank()) reject("Value is required");
        switch(d.get("type").textValue()) {
            case "TEXT" -> { if(s.codePointCount(0,s.length())>d.get("maxLength").intValue()) reject("Text exceeds length"); }
            case "ENUM" -> { boolean found=false; for(JsonNode option:d.get("options")) if(option.textValue().equals(s)) found=true;
                if(!found) reject("Option is not defined in this revision"); }
            case "NUMBER" -> {
                if(s.length()>100 || !s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) reject("Invalid decimal format");
                BigDecimal n=new BigDecimal(s);
                int scale=d.get("scale").intValue(), precision=d.get("precision").intValue();
                int integerDigits=n.signum()==0?0:Math.max(0,n.precision()-n.scale());
                if(n.scale()>scale || integerDigits>precision-scale) reject("Decimal exceeds precision or scale");
                if(d.has("minimum") && n.compareTo(decimal(d.get("minimum")))<0) reject("Below minimum");
                if(d.has("maximum") && n.compareTo(decimal(d.get("maximum")))>0) reject("Above maximum");
            }
            default -> reject("Unknown type");
        }
        return v;
    }
    public static void fields(JsonNode n, Set<String> allowed) {
        if(n==null || !n.isObject()) invalid("Request must be an object");
        for(String name:n.propertyNames()) if(!allowed.contains(name)) invalid("Unknown request field");
    }
    public static String text(JsonNode n) { if(n==null || !n.isTextual()) invalid("String required"); return n.textValue(); }
    private static int integer(JsonNode n,int min,int max) {
        if(n==null || !n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<min || n.intValue()>max) invalid("Invalid integer constraint");
        return n.intValue();
    }
    private static BigDecimal decimal(JsonNode n) {
        String s=text(n); if(s.length()>100 || !s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) invalid("Invalid decimal bound");
        return new BigDecimal(s);
    }
    private static void invalid(String message) { throw ProjectAccessException.invalid(message); }
    private static void reject(String message) { throw ProjectAccessException.unprocessable(message); }
}
