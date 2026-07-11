package org.pimalaya.cardamum;

import org.pimalaya.cardamum.client.Account;

/**
 * The built-in, undeletable "On this device" account and its single
 * addressbook. Its cards live only in the app's store: the remote sync
 * skips it (no server), and the phone projection stays opt-in, so a user
 * can add, edit and import contacts without ever connecting an account.
 *
 * <p>It is synthesized in memory (never persisted to the credential
 * store) and seeded into the card store's addressbook table on launch,
 * so a fresh install or a schema rebuild always finds it present and
 * subscribed. The {@code local://} base URL keeps it out of every
 * backend transport (see Native.accountInfo): it reports as a
 * transport-less {@code local} backend, non-account-level, so its cards
 * key per collection like CardDAV.
 */
final class LocalBook {
    /** Base URL and account-email key of the local account (never a real email). */
    static final String ACCOUNT = "local://on-this-device";

    /** Collection URL of the local account's single addressbook. */
    static final String URL = "local://on-this-device/contacts";

    /** Stable addressbook id of the local book. */
    static final String ID = "contacts";

    private LocalBook() {}

    /** True for the local account's email key or any local URL. */
    static boolean is(String emailOrUrl) {
        return emailOrUrl != null && emailOrUrl.startsWith("local://");
    }

    /** The synthesized account, its login and password unused (no transport). */
    static AccountEntry account() {
        return new AccountEntry(new Account(ACCOUNT, "", ""), ACCOUNT);
    }
}
