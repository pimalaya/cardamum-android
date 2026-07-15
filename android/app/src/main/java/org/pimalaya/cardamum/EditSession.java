package org.pimalaya.cardamum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;

/**
 * The editor's working state, one object per open contact: built up
 * when a card (or merged group, or conflict) opens and replaced by a
 * fresh blank when the editor leaves, so the form, the advanced and
 * source editors, the merge flow and the conflict resolution all read
 * one session instead of a dozen loose activity fields.
 */
final class EditSession {
    /** The card open in the editor (null card means composing a new one). */
    Addressbook book;

    String accountEmail;
    Card card;

    /** The vCard the editor patches: the card's, or a fresh template. */
    String vcard;

    /** The merged group's replicas behind the editor; saving fans out. */
    List<Entry> replicas = new ArrayList<>();

    /** The replica the Merge action keeps; every other card is removed. */
    Entry mergeSurvivor;

    /** The addressbooks dialog's desired state, applied on save. */
    Map<String, Boolean> pendingBookState;

    /** The editor's bar title (the shared bar is retitled per screen). */
    CharSequence title = "";

    /** Whether the open contact offers the advanced raw editor. */
    boolean advancedAvailable;

    /** Whether the editor is resolving a both-sides-edited sync
     *  conflict: the form's picks apply onto the captured three-way
     *  merge (vcard) and stage onto the one conflicted replica. */
    boolean resolvingConflict;

    /** The advanced editor's working document; null until it opens. */
    String advancedVcard;

    /** Set by a raw edit: staging must not skip on an unchanged hash. */
    boolean advancedDirty;
}
