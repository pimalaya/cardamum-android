package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Cards;
import org.pimalaya.cardamum.client.Card;

/**
 * The semi-automatic duplicate remover behind its app-bar icon: the
 * bridge scans every replica for exact normalized matches (emails,
 * phones, full names), and the first unhandled group surfaces as a
 * one-shot review dialog of tappable card rows with three verbs:
 * Merge runs the host's normal merge flow, Ignore remembers the
 * dismissal in the store, and Link (offered only when every card
 * lives in its own addressbook) makes the cards one contact by
 * staging a shared UID. Tap the icon again for the next group.
 */
final class DuplicateReview {
    private final MainActivity host;

    DuplicateReview(MainActivity host) {
        this.host = host;
    }

    /**
     * Scans the replicas for likely duplicates (computed by the
     * bridge, off the main thread) and reviews the first group that
     * is neither handled nor dismissed.
     */
    void find(List<Entry> replicas) {
        Map<String, Entry> byRef = new HashMap<>();
        JSONArray cards = new JSONArray();
        try {
            for (Entry entry : replicas) {
                String ref = host.pool.replicaRef(entry);
                if (byRef.putIfAbsent(ref, entry) == null) {
                    cards.put(new JSONObject().put("ref", ref).put("vcard", entry.card.vcard));
                }
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }

        host.io.execute(
                () -> {
                    List<Entry> first = null;
                    Exception failure = null;
                    try {
                        JSONArray found = Cards.findDuplicates(cards);
                        for (int index = 0; found != null && index < found.length(); index++) {
                            JSONArray refs = found.optJSONObject(index).optJSONArray("refs");
                            List<Entry> members = new ArrayList<>();
                            for (int at = 0; refs != null && at < refs.length(); at++) {
                                Entry entry = byRef.get(refs.optString(at));
                                if (entry != null) {
                                    members.add(entry);
                                }
                            }
                            if (members.size() >= 2
                                    && !host.base.isDuplicateDismissed(
                                            group(members).optString("key"))) {
                                first = members;
                                break;
                            }
                        }
                    } catch (Exception error) {
                        Log.w("cardamum", "duplicate scan failed", error);
                        failure = error;
                    }

                    List<Entry> pending = first;
                    Exception scanFailure = failure;
                    host.main.post(
                            () -> {
                                if (scanFailure != null) {
                                    host.showError(scanFailure, R.string.dup_none);
                                    return;
                                }
                                if (pending == null) {
                                    host.toast(host.getString(R.string.dup_none));
                                } else {
                                    review(pending);
                                }
                            });
                });
    }

    /**
     * Reviews the first duplicate group found, one shot: after any
     * verb, tapping the bar icon again surfaces the next group (a
     * handled group no longer matches, an ignored one is remembered).
     * Each card is a tappable row leaving the review for its editor.
     */
    private void review(List<Entry> members) {
        JSONObject dup = group(members);

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, host.ui.dp(8), 0, 0);

        AlertDialog[] shown = new AlertDialog[1];
        Set<String> seen = new HashSet<>();
        for (Entry entry : members) {
            if (!seen.add(host.pool.replicaRef(entry))) {
                continue;
            }

            TextView name = new TextView(host);
            name.setText(entry.displayName());
            name.setTextSize(15);
            name.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));

            TextView book = new TextView(host);
            book.setText(entry.book.name);
            book.setTextSize(13);
            book.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));

            TextView account = new TextView(host);
            account.setText(entry.accountEmail);
            account.setTextSize(12);
            account.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));

            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), host.ui.dp(8));
            row.setBackgroundResource(
                    host.ui.resolveAttr(android.R.attr.selectableItemBackground));
            row.addView(name);
            row.addView(book);
            row.addView(account);
            row.setOnClickListener(
                    view -> {
                        shown[0].dismiss();
                        host.openMerged(java.util.Collections.singletonList(entry));
                    });
            content.addView(row);
        }

        // NOTE: Link rides as a content action; the three dialog buttons
        // are taken by Merge, Ignore and Cancel.
        if (dup.optBoolean("linkable")) {
            TextView link = new TextView(host);
            link.setText(R.string.dup_link);
            link.setTextSize(14);
            link.setTextColor(host.ui.resolveColor(android.R.attr.colorAccent));
            link.setPadding(host.ui.dp(24), host.ui.dp(12), host.ui.dp(24), host.ui.dp(12));
            link.setBackgroundResource(
                    host.ui.resolveAttr(android.R.attr.selectableItemBackground));
            link.setOnClickListener(
                    view -> {
                        shown[0].dismiss();
                        link(members);
                    });
            content.addView(link);
        }

        shown[0] =
                new AlertDialog.Builder(host)
                        .setTitle(R.string.dup_title)
                        .setView(content)
                        .setPositiveButton(
                                R.string.dup_merge,
                                (dialog, which) -> host.mergeReplicas(members))
                        .setNeutralButton(
                                R.string.dup_ignore,
                                (dialog, which) ->
                                        host.base.dismissDuplicate(dup.optString("key")))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
    }

    /**
     * The group's duplicate-review facts from the bridge: its
     * dismissal key and whether Link may be offered.
     */
    private JSONObject group(List<Entry> members) {
        JSONArray refs = new JSONArray();
        try {
            for (Entry entry : members) {
                refs.put(
                        new JSONObject()
                                .put("ref", host.pool.replicaRef(entry))
                                .put("book", entry.book.url));
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return Cards.duplicateGroup(refs);
    }

    /**
     * Links the cards into one contact by staging the same UID on all
     * of them; transparent UID grouping does the rest.
     */
    private void link(List<Entry> members) {
        String uid = "";
        for (Entry entry : members) {
            if (!entry.uid.isEmpty()) {
                uid = entry.uid;
                break;
            }
        }
        if (uid.isEmpty()) {
            uid = UUID.randomUUID().toString();
        }

        try {
            Set<String> staged = new HashSet<>();
            for (Entry entry : members) {
                if (!staged.add(host.pool.replicaRef(entry)) || uid.equals(entry.uid)) {
                    continue;
                }
                AccountEntry owner = host.accountFor(entry.accountEmail);
                if (owner == null) {
                    continue;
                }
                String vcard = Cards.setCardUid(entry.card.vcard, uid);
                host.base.saveLocal(
                        entry.accountEmail,
                        ContactPool.cardKey(owner.account, entry.book.url, entry.card.id),
                        entry.book.url,
                        new Card(entry.card.id, entry.card.uri, entry.card.etag, vcard));
            }
        } catch (Exception error) {
            host.showError(error, R.string.save_failed);
        }
        host.reloadContacts();
    }
}
