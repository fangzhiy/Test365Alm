package com.test365alm.server.asset;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;

/** Content detection only: no document parsers, filename hints or remote resources. */
public final class FileRules {
    private FileRules() { }
    public record Content(Path path,long size,String sha256,String mime) implements AutoCloseable {
        public void close() throws IOException {Files.deleteIfExists(path);}
    }
    public static String safeFilename(String name){return AssetRules.safeFilename(name);}
    public static Content inspect(InputStream input,long declaredSize,String declaredSha,String declaredMime) throws IOException {
        if(declaredSize<1||declaredSize>AssetRules.MAX_SIZE)throw com.test365alm.server.project.ProjectAccessException.invalid("Invalid declared size");
        Path path=Files.createTempFile("test365alm-upload-",".bin"); boolean retained=false;
        try {
            MessageDigest digest;
            try {digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
            long size;
            try(OutputStream file=Files.newOutputStream(path);DigestOutputStream out=new DigestOutputStream(file,digest)) {
                size=AssetRules.readBounded(input,out);
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            String mime;
            try(InputStream file=new BufferedInputStream(Files.newInputStream(path))) {mime=new Tika().detect(file);}
            // Tika handles executable magic, HTML/SVG/XML and known script MIME. Plain text
            // additionally rejects recognizable active-script prefixes; this is not a sandbox.
            byte[] prefix;
            try(InputStream file=Files.newInputStream(path)){prefix=file.readNBytes(8192);}
            String text=new String(prefix,StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if(!AssetRules.MIME.contains(mime)||text.startsWith("#!")||text.startsWith("<?php")||text.startsWith("@echo")
                    ||text.startsWith("<script")||text.startsWith("<svg")||text.startsWith("<html")
                    ||text.startsWith("<!doctype html")||text.matches("(?s)^(function\\s|import\\s|export\\s|const\\s|let\\s|var\\s).*"))
                throw AssetRules.error("UNSUPPORTED_FILE_TYPE",HttpStatus.UNSUPPORTED_MEDIA_TYPE,"The actual file type is not allowed");
            if(size!=declaredSize||!hash.equals(declaredSha)||!mime.equals(declaredMime))
                throw AssetRules.error("CONTENT_MISMATCH",HttpStatus.UNPROCESSABLE_ENTITY,"The content does not match its declaration");
            retained=true;return new Content(path,size,hash,mime);
        } finally {if(!retained)Files.deleteIfExists(path);}
    }
}
