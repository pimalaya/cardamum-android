package org.pimalaya.cardamum;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.Cards;
import org.pimalaya.cardamum.client.CardamumClient;

/**
 * The contact save fan-out behind the edit form: it reads the form
 * model and the working {@link EditSession}, stages the edited contact
 * into the base through the offline engine (offline first, nothing
 * touches the network until the next sync), and applies the
 * addressbooks dialog's placement. It reaches the store, pool and
 * accounts through the host; the host keeps the navigation and calls in
 * through {@link #save}.
 */
final class ContactWriter {
    private final MainActivity host;

    ContactWriter(MainActivity host) {
        this.host = host;
    }

    /**
     * Stages the edited contact in the base (offline first: nothing
     * touches the network until the next sync pushes it) and returns to
     * the list. The form model applies to every replica's own document
     * (docs/merged-view.md): each keeps its UID and unmanaged
     * properties while the managed content converges; replicas already
     * carrying the resulting content are skipped.
     */
    void save() {
        String birthday = host.form.birthday();
        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            host.toast(host.getString(R.string.invalid_birthday));
            return;
        }
        String anniversary = host.form.anniversary();
        if (!anniversary.isEmpty() && !anniversary.matches("\\d{4}-\\d{2}-\\d{2}")) {
            host.toast(host.getString(R.string.invalid_anniversary));
            return;
        }

        try {
            JSONObject model = host.form.collect();

            if (host.edit.card == null) {
                AccountEntry account = host.accountFor(host.edit.accountEmail);
                if (account == null) {
                    // The account was deleted while the editor was open.
                    host.toast(host.getString(R.string.save_failed));
                    return;
                }
                String source =
                        host.edit.advancedVcard != null
                                ? host.edit.advancedVcard
                                : host.edit.vcard;
                String vcard = Cards.applyCard(source, model);
                String uid = host.cardIndex(vcard).optString("uid");
                String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                host.base.saveLocal(
                        host.edit.accountEmail,
                        ContactPool.cardKey(account.account, host.edit.book.url, id),
                        host.edit.book.url,
                        new Card(id, null, null, vcard));
            } else if (host.edit.resolvingConflict) {
                saveConflictResolution(model);
            } else if (host.edit.mergeSurvivor != null) {
                saveMerge(model);
            } else {
                saveFanOut(model);
            }

            // The copies carry the just-saved content.
            if (host.edit.card != null && host.edit.pendingBookState != null) {
                applyBookState(Cards.applyCard(host.edit.card.vcard, model));
            }
        } catch (Exception error) {
            host.showError(error, R.string.save_failed);
            return;
        }

        host.reloadContacts();
        host.showBack(MainActivity.PANEL_CONTACTS);
    }

    /**
     * The fan-out save: the form model applies to every card's own
     * document, each staged through the engine (editing a conflicted
     * replica resolves it).
     */
    private void saveFanOut(JSONObject model) throws JSONException {
        OfflineEngine engine = new OfflineEngine(host.base, host.client, null, null);
        java.util.Set<String> staged = new java.util.HashSet<>();

        for (Entry entry : host.edit.replicas) {
            // NOTE: an m:n replica appears once per book; stage it once.
            if (!staged.add(host.pool.replicaRef(entry))) {
                continue;
            }

            // NOTE: a raw edit must stage even when the managed-content
            // hash is unchanged, so the advanced document stands in.
            String source =
                    host.edit.advancedVcard != null ? host.edit.advancedVcard : entry.card.vcard;
            String vcard = Cards.applyCard(source, model);
            if (!host.edit.advancedDirty
                    && host.cardIndex(vcard).optString("hash").equals(entry.hash)) {
                continue;
            }
            engine.mutateEdit(
                    entry.book.url,
                    CardStore.rowHandle(entry.book.url, entry.card.uri, entry.card.id),
                    vcard);
        }
    }

    /**
     * The conflict-resolution save: the form's picks apply onto the
     * captured three-way merge (so remote's non-conflicting changes and
     * unmanaged data survive), staged onto the one conflicted replica.
     * Editing the conflicted placement resolves it in the engine, and the
     * next sync pushes it guarded on the observed remote revision.
     */
    private void saveConflictResolution(JSONObject model) throws JSONException {
        Entry replica = host.edit.replicas.get(0);
        String resolved = Cards.applyCard(host.edit.vcard, model);
        new OfflineEngine(host.base, host.client, null, null)
                .mutateEdit(
                        replica.book.url,
                        CardStore.rowHandle(replica.book.url, replica.card.uri, replica.card.id),
                        resolved);
    }

    /**
     * The merge save: the form model applies to the surviving card and
     * every other card behind the form stages a delete.
     */
    private void saveMerge(JSONObject model) throws JSONException {
        Entry survivor = host.edit.mergeSurvivor;
        host.edit.mergeSurvivor = null;

        String vcard = Cards.applyCard(survivor.card.vcard, model);
        if (!host.cardIndex(vcard).optString("hash").equals(survivor.hash)) {
            new OfflineEngine(host.base, host.client, null, null)
                    .mutateEdit(
                            survivor.book.url,
                            CardStore.rowHandle(
                                    survivor.book.url, survivor.card.uri, survivor.card.id),
                            vcard);
        }

        String survivorRef = host.pool.replicaRef(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : host.edit.replicas) {
            String ref = host.pool.replicaRef(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = host.accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            host.base.markDeleted(
                    entry.accountEmail,
                    ContactPool.cardKey(owner.account, entry.book.url, entry.card.id),
                    entry.card);
        }

        host.toast(host.getString(R.string.merge_done));
    }

    /**
     * Applies the addressbooks dialog's desired state on save: staged
     * memberships, copies (carrying the just-saved content), removals.
     */
    private void applyBookState(String vcard) {
        Map<String, Boolean> desired = host.edit.pendingBookState;
        host.edit.pendingBookState = null;
        if (desired == null) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : host.edit.replicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        for (BookEntry target : host.base.loadSubscribedAddressbooks()) {
            Boolean wanted = desired.get(target.book.url);
            if (wanted == null || wanted == replicaByBook.containsKey(target.book.url)) {
                continue;
            }
            AccountEntry account = host.accountFor(target.accountEmail);
            if (account == null) {
                continue;
            }

            if (wanted) {
                addToBook(target, account, vcard);
            } else {
                removeFromBook(target, replicaByBook);
            }
        }
    }

    /**
     * Adds the contact to an addressbook: a staged membership when the
     * contact already has a card on that account-level backend, a
     * copied card sharing the vCard UID anywhere else.
     */
    private void addToBook(BookEntry target, AccountEntry account, String vcard) {
        if (CardamumClient.isAccountLevel(account.account)) {
            for (Entry entry : host.edit.replicas) {
                if (entry.accountEmail.equals(target.accountEmail)) {
                    host.base.stageMembership(
                            entry.accountEmail,
                            ContactPool.cardKey(account.account, entry.book.url, entry.card.id),
                            target.book.url,
                            true);
                    return;
                }
            }
        }

        String uid = host.cardIndex(vcard).optString("uid");
        String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
        host.createCopy(target, account, id, vcard);
    }

    /**
     * Removes the contact from an addressbook: a staged membership
     * removal when its card keeps other books, a staged delete of the
     * card otherwise.
     */
    private void removeFromBook(BookEntry target, Map<String, Entry> replicaByBook) {
        Entry replica = replicaByBook.get(target.book.url);
        if (replica == null) {
            return;
        }
        AccountEntry owner = host.accountFor(replica.accountEmail);
        if (owner == null) {
            return;
        }
        String replicaKey = ContactPool.cardKey(owner.account, replica.book.url, replica.card.id);

        if (CardamumClient.isAccountLevel(owner.account)
                && host.base.loadMemberships(replica.accountEmail, replicaKey).size() > 1) {
            host.base.stageMembership(replica.accountEmail, replicaKey, target.book.url, false);
        } else {
            // Last place of this card, so the card itself goes.
            host.base.markDeleted(replica.accountEmail, replicaKey, replica.card);
        }
    }
}
