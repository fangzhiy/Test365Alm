package com.test365alm.server.asset;

import java.util.UUID;

/** Independent trusted scanner identity; the ordinary application runtime cannot forge attestations. */
public interface ScanResultWriter extends AutoCloseable {
    UUID record(UUID tenant,UUID project,UUID upload,UUID actor,String sha256,MalwareScanner.ScanResult result);
    @Override default void close() { }
}
