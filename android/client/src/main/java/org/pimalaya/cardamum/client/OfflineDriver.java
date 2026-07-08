package org.pimalaya.cardamum.client;

/**
 * Consumer seam of the io-offline engine: the Rust bridge drives the
 * engine's coroutines and upcalls {@link #serve} with one JSON
 * envelope per yield (storage reads and writes, remote enumerate,
 * fetch and push). The implementation services the request and
 * returns the matching JSON reply; a reply carrying an {@code error}
 * field aborts the drive and surfaces as the operation's error.
 */
public interface OfflineDriver {
    /** Services one engine yield, returning its JSON reply. */
    String serve(String yieldJson);
}
