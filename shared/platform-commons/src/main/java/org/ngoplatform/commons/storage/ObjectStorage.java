package org.ngoplatform.commons.storage;

import java.io.InputStream;

/** Storage adapter (spec §3.7). Objects are private; clients download through authorised service endpoints. */
public interface ObjectStorage {
    void put(String key, byte[] content, String contentType);
    InputStream get(String key);
    boolean exists(String key);
    /** Removes the object; missing objects are ignored (idempotent, used for erasure). */
    void delete(String key);
}
