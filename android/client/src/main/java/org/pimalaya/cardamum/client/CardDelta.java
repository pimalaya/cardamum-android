package org.pimalaya.cardamum.client;

import java.util.List;

/**
 * Delta of a CardDAV sync-collection REPORT (RFC 6578): the cards
 * changed since the request token (ETags only, no body), the resource
 * names removed, and the next token to checkpoint. A rejected token
 * sets {@link #invalidToken} instead, so the caller falls back to a
 * full enumeration.
 */
public final class CardDelta {
    /** Cards created or updated since the request token (no body). */
    public final List<Card> changed;

    /** Resource names removed since the request token. */
    public final List<String> vanished;

    /** The next checkpoint token, null when the server sent none. */
    public final String token;

    /** True when the server rejected the request token. */
    public final boolean invalidToken;

    CardDelta(List<Card> changed, List<String> vanished, String token, boolean invalidToken) {
        this.changed = changed;
        this.vanished = vanished;
        this.token = token;
        this.invalidToken = invalidToken;
    }
}
