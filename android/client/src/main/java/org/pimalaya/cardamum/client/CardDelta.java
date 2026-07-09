package org.pimalaya.cardamum.client;

import java.util.List;

/**
 * Incremental changes of one collection since a sync cursor: the cards
 * changed (spine-only or full, per backend), the resource names
 * removed, the next cursor to checkpoint, and whether the round listed
 * the complete member set (an initial round, or an expired cursor the
 * bridge re-ran as one).
 */
public final class CardDelta {
    /** Cards created or updated since the cursor. */
    public final List<Card> changed;

    /** Resource names removed since the cursor. */
    public final List<String> vanished;

    /** The next checkpoint cursor, null when the server sent none. */
    public final String token;

    /** True when the round listed the complete member set. */
    public final boolean complete;

    CardDelta(List<Card> changed, List<String> vanished, String token, boolean complete) {
        this.changed = changed;
        this.vanished = vanished;
        this.token = token;
        this.complete = complete;
    }
}
