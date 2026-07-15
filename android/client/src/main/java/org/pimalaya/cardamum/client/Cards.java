package org.pimalaya.cardamum.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The transport-free half of the bridge: pure vCard computations and
 * io-offline plan verbs (facts in, decision out) that never open a
 * socket. Static on purpose, mirroring the stateless bridge: every
 * call crosses JNI with everything it needs and returns everything it
 * produced. The network operations and the engine drives stay on
 * {@link CardamumClient}.
 */
public final class Cards {
    private Cards() {}

    /**
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk).
     */
    public static boolean offlineRetryUnguarded(JSONObject facts) {
        return CardamumClient.object(Native.offlineRetryUnguarded(facts.toString()))
                .optBoolean("retry");
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate ({@code {members, vanished}}).
     */
    public static JSONObject offlineAccountSnapshot(JSONObject facts) {
        return CardamumClient.object(Native.offlineAccountSnapshot(facts.toString()));
    }

    /** Plans one push change ({@code {action, postCreateBooks?}}). */
    public static JSONObject offlinePushPlan(JSONObject facts) {
        return CardamumClient.object(Native.offlinePushPlan(facts.toString()));
    }

    /**
     * Maps one card-plus-membership row to its engine placement on the
     * server axis, null when the row surfaces nowhere.
     */
    public static JSONObject offlinePlacement(JSONObject facts) {
        return CardamumClient.object(Native.offlinePlacement(facts.toString()))
                .optJSONObject("placement");
    }

    /**
     * Maps one card-plus-membership row to its phone-axis placement,
     * null when the row surfaces nowhere.
     */
    public static JSONObject offlinePhonePlacement(JSONObject facts) {
        return CardamumClient.object(Native.offlinePhonePlacement(facts.toString()))
                .optJSONObject("placement");
    }

    /**
     * Plans one engine upsert onto the card and membership rows
     * ({@code {action, row?, memberState?}}).
     */
    public static JSONObject offlineUpsertPlan(JSONObject facts) {
        return CardamumClient.object(Native.offlineUpsertPlan(facts.toString()));
    }

    /** Plans one phone-axis upsert ({@code {action, row?, axis}}). */
    public static JSONObject offlinePhoneUpsertPlan(JSONObject facts) {
        return CardamumClient.object(Native.offlinePhoneUpsertPlan(facts.toString()));
    }

    /** Plans a phone-collection drop ({@code {action}}). */
    public static JSONObject offlinePhoneDropPlan(JSONObject facts) {
        return CardamumClient.object(Native.offlinePhoneDropPlan(facts.toString()));
    }

    /**
     * Projects the card's vCard onto the neutral field model the app
     * maps to ContactsContract rows (docs/contacts-mapping.md).
     */
    public static JSONObject projectCard(Card card) {
        return CardamumClient.object(Native.projectCard(card.vcard));
    }

    /** Projects a raw vCard document onto the neutral field model. */
    public static JSONObject projectCard(String vcard) {
        return CardamumClient.object(Native.projectCard(vcard));
    }

    /**
     * Indexes a vCard for the store: the display fields the contacts
     * list renders (name, first email and phone, UID) and a normalized
     * content hash for the divergence flag of linked replicas.
     */
    public static JSONObject indexCard(String vcard) {
        return CardamumClient.object(Native.indexCard(vcard));
    }

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every property the model does not manage.
     */
    public static String applyCard(String vcard, JSONObject model) {
        return CardamumClient.string(
                CardamumClient.object(Native.applyCard(vcard, model.toString())), "vcard");
    }

    /**
     * Merges several vCard documents into one union: the merged
     * document, its field model (the merge form prefill) and the
     * per-field alternative values.
     */
    public static JSONObject mergeCards(List<String> vcards) {
        JSONArray cards = new JSONArray();
        for (String vcard : vcards) {
            cards.put(vcard);
        }
        return CardamumClient.object(Native.mergeCards(cards.toString()));
    }

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base (empty means
     * unknown; the local side stands in). The local side wins
     * same-field collisions; every other remote change flows in.
     */
    public static String mergeCardChanges(String base, String local, String remote) {
        return CardamumClient.string(
                CardamumClient.object(Native.mergeCardChanges(base, local, remote)), "vcard");
    }

    /**
     * Builds the conflict form's inputs for a both-sides-edited row: the
     * merged document with the newer side (by REV) winning collisions as
     * the pre-filled default, its field model, the two candidate values of
     * every genuinely conflicted field, and an (always empty, non-null)
     * changed list. An empty {@code alternatives} means nothing needs the
     * user.
     */
    public static JSONObject mergeConflictForm(String base, String local, String remote) {
        return CardamumClient.object(Native.mergeConflictForm(base, local, remote));
    }

    /**
     * Rewrites the card's UID, preserving every other byte (a plain
     * copy is a new identity).
     */
    public static String setCardUid(String vcard, String uid) {
        return CardamumClient.string(
                CardamumClient.object(Native.setCardUid(vcard, uid)), "vcard");
    }

    /**
     * Finds groups of likely-duplicate cards among {@code {ref,
     * vcard}} pairs: exact normalized email, phone or full-name
     * matches, conservative on purpose.
     */
    public static JSONArray findDuplicates(JSONArray cards) {
        return CardamumClient.object(Native.findDuplicates(cards.toString()))
                .optJSONArray("groups");
    }

    /**
     * Lists the card's raw property lines for the advanced editor, in
     * source order, unfolded.
     */
    public static JSONArray cardProps(String vcard) {
        return CardamumClient.object(Native.cardProps(vcard)).optJSONArray("props");
    }

    /**
     * Rewrites one raw property line for the advanced editor: the line
     * replaces the property at the index (a blank line removes it),
     * index -1 appends.
     */
    public static String cardSetProp(String vcard, int index, String line) {
        return CardamumClient.string(
                CardamumClient.object(Native.cardSetProp(vcard, index, line)), "vcard");
    }

    /**
     * Recomposes one property from its structured parts ({@code {name,
     * params: [{name, values}], value}}) and rewrites it (index -1
     * appends).
     */
    public static String cardSetPropParts(String vcard, int index, JSONObject prop) {
        return CardamumClient.string(
                CardamumClient.object(Native.cardSetPropParts(vcard, index, prop.toString())),
                "vcard");
    }

    /**
     * The component labels of a structured property name (N, ADR,
     * GENDER), empty for plain values: the advanced editor shapes its
     * value form from them.
     */
    public static JSONArray cardPropLabels(String name) {
        return CardamumClient.object(Native.cardPropLabels(name)).optJSONArray("labels");
    }

    /**
     * The ordered type-set vocabulary the edit form's spinners address
     * for the kind ({@code phone}, {@code email}, {@code address},
     * {@code relation}, {@code gender}): each position's vCard TYPE set
     * (a one-element list of the sex code for {@code gender}), in the
     * order the Android string-arrays mirror. The Rust side owns the
     * order; a test pins the arrays to it.
     */
    public static JSONArray cardTypeOrder(String kind) {
        return CardamumClient.object(Native.cardTypeOrder(kind)).optJSONArray("order");
    }

    /**
     * Validates a hand-edited vCard source (it must reparse) and
     * returns it re-serialized.
     */
    public static String cardSource(String vcard) {
        return CardamumClient.string(CardamumClient.object(Native.cardSource(vcard)), "vcard");
    }

    /**
     * The edit form's view support computed from the field model:
     * summaries, type spinner positions and picker dates.
     */
    public static JSONObject formView(JSONObject model) {
        return CardamumClient.object(Native.formView(model.toString()));
    }

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position.
     */
    public static JSONObject formEntry(String kind, int index, String value, boolean pref) {
        return CardamumClient.object(Native.formEntry(kind, index, value, pref));
    }

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month).
     */
    public static String formDate(int year, int month, int day) {
        return CardamumClient.string(
                CardamumClient.object(Native.formDate(year, month, day)), "value");
    }

    /**
     * Groups the replica pool ({@code {replicas, links, detached}})
     * into merged contacts, the groups sorted by primary display name.
     */
    public static JSONObject groupContacts(JSONObject input) {
        return CardamumClient.object(Native.groupContacts(input.toString()));
    }

    /**
     * The duplicate review's group facts ({@code {key, linkable}})
     * from its {@code {ref, book}} members.
     */
    public static JSONObject duplicateGroup(JSONArray members) {
        return CardamumClient.object(Native.duplicateGroup(members.toString()));
    }
}
