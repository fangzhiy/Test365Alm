package com.test365alm.server.asset;

/** Intentionally omits endpoint, credentials, keys and remote response text. */
public final class AssetStorageException extends RuntimeException {
    public AssetStorageException() { super("Private asset storage is unavailable"); }
}
