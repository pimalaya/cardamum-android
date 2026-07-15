package org.pimalaya.cardamum;

import java.util.ArrayList;
import java.util.List;

/**
 * One merged row: the replicas sharing a logical contact (same vCard
 * UID across accounts and addressbooks, grouped by the bridge); a card
 * without a UID stays a singleton group.
 */
final class Group {
    final String key;
    final List<Entry> replicas = new ArrayList<>();

    Group(String key) {
        this.key = key;
    }

    Entry primary() {
        return replicas.get(0);
    }

    /** True when any replica is a both-sides-edited sync conflict
     *  awaiting the user's manual resolution. */
    boolean conflicted() {
        for (Entry entry : replicas) {
            if (entry.conflicted) {
                return true;
            }
        }
        return false;
    }
}
