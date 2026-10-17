package com.test365alm.server.asset;

import java.io.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import com.test365alm.server.metadata.FieldRules;
import com.test365alm.server.project.ProjectAccessException;

/** Closed request schemas; filenames are metadata, never a storage or filesystem address. */
public final class AssetRules {
    public static final long MAX_SIZE=25L*1024*1024;
    public static final Set<String> MIME=Set.of("text/plain","application/pdf","image/png","image/jpeg");
    private AssetRules() { }
    public record UploadCommand(String filename,long size,String sha256,String contentType,UUID assetId) { }
    public record LinkCommand(String kind,UUID target,UUID assetId,UUID revisionId) { }
    public static UploadCommand upload(JsonNode n) {
        FieldRules.fields(n,Set.of("filename","size","sha256","contentType","assetId"));
        String name=safeFilename(FieldRules.text(n.get("filename")));
        JsonNode size=n.get("size");
        if(size==null||!size.isIntegralNumber()||!size.canConvertToLong()||size.longValue()<1||size.longValue()>MAX_SIZE)throw ProjectAccessException.invalid("size must be an integer within 1..26214400");
        String hash=FieldRules.text(n.get("sha256")),mime=FieldRules.text(n.get("contentType"));
        if(!hash.matches("[a-f0-9]{64}"))throw ProjectAccessException.invalid("sha256 must be 64 lower-case hexadecimal characters");
        if(!MIME.contains(mime))throw ProjectAccessException.invalid("contentType is not allowed");
        return new UploadCommand(name,size.longValue(),hash,mime,n.has("assetId")?uuid(n.get("assetId")):null);
    }
    public static String safeFilename(String raw) {
        if(raw==null||raw.isBlank()||raw.length()>200)throw ProjectAccessException.invalid("filename must contain 1..200 characters");
        String safe=raw.replaceAll("[\\p{Cntrl}\\\\/\";]","").trim();
        if(safe.isBlank())throw ProjectAccessException.invalid("filename is empty after sanitization");
        return safe;
    }
    public static LinkCommand link(JsonNode n) {
        FieldRules.fields(n,Set.of("kind","requirementId","testCaseId","defectId","assetId","assetRevisionId"));
        String kind=FieldRules.text(n.get("kind"));String field=targetField(kind);
        for(String other:List.of("requirementId","testCaseId","defectId"))if(!other.equals(field)&&n.has(other))throw ProjectAccessException.invalid("Exactly one typed attachment target is required");
        return new LinkCommand(kind,uuid(n.get(field)),uuid(n.get("assetId")),uuid(n.get("assetRevisionId")));
    }
    public static String targetField(String kind) {
        if(kind==null)throw ProjectAccessException.invalid("kind is required");
        return switch(kind){case "REQUIREMENT"->"requirementId";case "TEST_CASE"->"testCaseId";case "DEFECT"->"defectId";default->throw ProjectAccessException.invalid("Unsupported attachment kind");};
    }
    public static UUID uuid(JsonNode n) {
        String s=FieldRules.text(n);try{if(!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException();return UUID.fromString(s);}catch(IllegalArgumentException ex){throw ProjectAccessException.invalid("Invalid UUID");}
    }
    public static long readBounded(InputStream input,OutputStream output)throws IOException {
        byte[] buffer=new byte[8192];long count=0;int n;
        while((n=input.read(buffer))!=-1){count+=n;if(count>MAX_SIZE)throw error("FILE_TOO_LARGE",HttpStatus.PAYLOAD_TOO_LARGE,"File exceeds 25MiB");output.write(buffer,0,n);}return count;
    }
    public static ProjectAccessException error(String code,HttpStatus status,String message){return new ProjectAccessException(code,status,message);}
}
