package org.pimalaya.cardamum;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * The merged contacts pool behind the list (docs/merged-view.md): the
 * replicas of every subscribed book with their write-time index, and
 * the bridge grouping that merges them into logical contacts (UID
 * natural keys, detached refs and link clusters resolved by
 * groupContacts). Loading and grouping are blocking (a full table scan
 * plus one JNI round), so the callers run them off the main thread.
 */
final class ContactPool {
    private final CardStore base;
    private final CardamumClient client;

    /** The host's in-memory account cache, read on the main thread
     *  only ({@link #replicaRef}); the io-side grouping receives a
     *  snapshot instead. */
    private final List<AccountEntry> accounts;

    ContactPool(CardStore base, CardamumClient client, List<AccountEntry> accounts) {
        this.base = base;
        this.client = client;
        this.accounts = accounts;
    }

    /** Every subscribed book's replicas, in store order. */
    List<Entry> loadEntries() {
        List<Entry> entries = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            for (CardStore.Indexed indexed : base.loadIndexedCards(entry.book.url)) {
                entries.add(new Entry(entry.book, entry.accountEmail, indexed));
            }
        }
        return entries;
    }

    /**
     * Groups the replicas into merged rows through the bridge, the
     * link layer (manual links, detached refs) riding along. The
     * accounts snapshot resolves each replica's storage key without
     * touching the live cache off the main thread.
     */
    List<Group> group(List<Entry> entries, List<AccountEntry> accounts) {
        JSONObject pool;
        try {
            JSONArray replicas = new JSONArray();
            for (Entry entry : entries) {
                replicas.put(
                        new JSONObject()
                                .put("ref", replicaRef(entry, accounts))
                                .put("uid", entry.uid)
                                .put("name", entry.name)
                                .put("id", entry.card.id));
            }
            pool =
                    new JSONObject()
                            .put("replicas", replicas)
                            .put("links", new JSONObject(base.loadLinks()))
                            .put("detached", new JSONArray(base.loadDetached()));
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        JSONArray grouped = client.groupContacts(pool).optJSONArray("groups");

        List<Group> groups = new ArrayList<>();
        for (int at = 0; grouped != null && at < grouped.length(); at++) {
            JSONObject row = grouped.optJSONObject(at);
            Group group = new Group(row.optString("key"));
            JSONArray members = row.optJSONArray("replicas");
            for (int index = 0; members != null && index < members.length(); index++) {
                group.replicas.add(entries.get(members.optInt(index)));
            }
            groups.add(group);
        }
        return groups;
    }

    /** The replica's link-layer reference, over the live account cache. */
    String replicaRef(Entry entry) {
        return replicaRef(entry, accounts);
    }

    /** The distinct physical cards behind a merged row's replicas. */
    Set<String> distinctRefs(List<Entry> replicas) {
        Set<String> refs = new HashSet<>();
        for (Entry entry : replicas) {
            refs.add(replicaRef(entry));
        }
        return refs;
    }

    /**
     * The replica's storage key (docs/merged-view.md): the bare server
     * id on account-level backends, the collection-scoped key on
     * per-collection ones.
     */
    static String cardKey(Account account, String bookUrl, String id) {
        return CardamumClient.isAccountLevel(account) ? id : CardStore.key(bookUrl, id);
    }

    private static String replicaRef(Entry entry, List<AccountEntry> accounts) {
        AccountEntry account = null;
        for (AccountEntry candidate : accounts) {
            if (candidate.email.equals(entry.accountEmail)) {
                account = candidate;
            }
        }
        String key =
                account == null
                        ? CardStore.key(entry.book.url, entry.card.id)
                        : cardKey(account.account, entry.book.url, entry.card.id);
        return CardStore.replicaRef(entry.accountEmail, key);
    }
}
