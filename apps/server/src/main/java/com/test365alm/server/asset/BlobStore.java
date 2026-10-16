package com.test365alm.server.asset;

import java.io.InputStream;

/** Server-only object addresses. No ACL, public URL or presigned download API. */
public interface BlobStore {
    void putQuarantine(String key, InputStream content, long size);
    InputStream openQuarantine(String key);
    void publish(String quarantineKey, String publishedKey);
    InputStream openPublished(String key);
    void deleteQuarantine(String key);
}
