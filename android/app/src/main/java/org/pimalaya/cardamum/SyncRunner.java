package org.pimalaya.cardamum;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.CardamumException;
import org.pimalaya.cardamum.client.OauthTokens;

/**
 * The one sync path behind every entry point: the in-app sync and the
 * background worker both run their passes here, so the account lookup,
 * the addressbook self-heal and the refresh-once-and-retry dance exist
 * exactly once. The callers keep what is theirs alone: MainActivity the
 * loader dialog, the toasts and its in-memory account cache (fed by the
 * observer), SyncWorker the scheduling and the notification. Everything
 * blocks; callers run it off the main thread.
 */
final class SyncRunner {
    /**
     * Observes a run for the foreground loader and the account cache;
     * every hook fires on the sync thread.
     */
    interface Observer {
        /** A book's pass is starting (the loader titles itself). */
        void bookStarted(BookEntry book);

        /** An engine stage stepped (the loader's detail line). */
        void step(int stage, int count);

        /** A token refresh re-persisted the account's credentials. */
        void accountRefreshed(AccountEntry updated);
    }

    /**
     * Outcome of a remote run: what was pulled from the server, pushed
     * to it, merged on the phone axis, and left conflicted awaiting the
     * user's manual resolution, plus the first failure.
     */
    static final class Outcome {
        final Set<String> localIn = new HashSet<>();
        final Set<String> localOut = new HashSet<>();
        final Set<String> localChanged = new HashSet<>();
        final Set<String> remoteIn = new HashSet<>();
        final Set<String> remoteOut = new HashSet<>();
        final Set<String> remoteChanged = new HashSet<>();
        int conflicts;

        /** Whether any synced book mirrors into the phone's Contacts
         *  app, which is what earns the report its Local line. */
        boolean local;

        Exception failure;

        /** Folds one book's report in; the sets dedupe across passes. */
        void absorb(OfflineEngine.Report report) {
            localIn.addAll(report.localIn);
            localOut.addAll(report.localOut);
            localChanged.addAll(report.localChanged);
            remoteIn.addAll(report.remoteIn);
            remoteOut.addAll(report.remoteOut);
            remoteChanged.addAll(report.remoteChanged);
            conflicts += report.conflicts;
        }
    }

    private final Context context;
    private final CardStore base;
    private final SecureStore store;
    private final CardamumClient client;

    /** Null when the run is headless (the background worker). */
    private final Observer observer;

    SyncRunner(
            Context context,
            CardStore base,
            SecureStore store,
            CardamumClient client,
            Observer observer) {
        this.context = context;
        this.base = base;
        this.store = store;
        this.client = client;
        this.observer = observer;
    }

    /**
     * One book's full pass, the background worker's unit. The local
     * book has no server, so its pass is the phone spoke alone; a
     * server book runs the full three-spoke pass with its account's
     * stored credentials, an expired OAuth access token refreshed and
     * the book retried once.
     */
    OfflineEngine.Report syncBook(BookEntry book) throws Exception {
        String url = book.book.url;
        bookStarted(book);

        if (LocalBook.is(book.accountEmail)) {
            OfflineEngine.Report report = new OfflineEngine.Report();
            engine(null).syncPhone(url, report);
            return report;
        }

        AccountEntry entry = entryFor(book.accountEmail);
        if (entry == null) {
            Log.w("cardamum", "no stored account for " + book.accountEmail + ", skipping");
            return new OfflineEngine.Report();
        }

        try {
            return engine(entry.account).syncBook(url, book.remoteSynced);
        } catch (Exception error) {
            if (!expiredToken(error) || entry.refreshToken == null) {
                throw error;
            }
            return engine(refresh(entry)).syncBook(url, book.remoteSynced);
        }
    }

    /**
     * The full remote run, the in-app sync's unit: self-heals accounts
     * whose addressbooks a schema rebuild dropped, then reconciles
     * every subscribed book account by account, one engine per account
     * so the account-level backends (JMAP, Google) list their cards
     * once per pass. An expired OAuth access token refreshes and
     * retries its account once; one account failing (revoked token,
     * server down) never blocks the others, the first failure riding
     * the outcome instead.
     */
    Outcome syncRemote() {
        Outcome outcome = new Outcome();

        // NOTE: self-heal an account whose addressbooks a schema rebuild
        // dropped by re-fetching them all-subscribed. The local account
        // never appears here (synthesized in memory, seeded on launch).
        Set<String> known = new HashSet<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            known.add(entry.accountEmail);
        }
        for (AccountEntry account : store.loadAll()) {
            if (known.contains(account.email)) {
                continue;
            }
            try {
                base.replaceAddressbooks(account.email, client.listAddressbooks(account.account));
            } catch (Exception error) {
                Log.w("cardamum", "addressbook recovery failed", error);
            }
        }

        Map<String, List<BookEntry>> byAccount = new LinkedHashMap<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            byAccount
                    .computeIfAbsent(entry.accountEmail, email -> new ArrayList<>())
                    .add(entry);
        }

        for (Map.Entry<String, List<BookEntry>> group : byAccount.entrySet()) {
            if (LocalBook.is(group.getKey())) {
                continue;
            }
            AccountEntry entry = entryFor(group.getKey());
            if (entry == null) {
                continue;
            }

            try {
                syncAccount(entry.account, group.getValue(), outcome);
            } catch (Exception error) {
                if (expiredToken(error) && entry.refreshToken != null) {
                    try {
                        syncAccount(refresh(entry), group.getValue(), outcome);
                        continue;
                    } catch (Exception retryError) {
                        error = retryError;
                    }
                }

                // NOTE: one account failing (revoked token, server down)
                // must not block the others.
                Log.w("cardamum", "sync failed for " + group.getKey(), error);
                if (outcome.failure == null) {
                    outcome.failure = error;
                }
            }
        }

        return outcome;
    }

    /**
     * The phone spoke alone: reconciles the per-addressbook Android
     * accounts (which also purges a book just switched off), then runs
     * the two-way phone engine pass per phone-synced book, tallying
     * into the report. Returns a failure, or null. Needs the contacts
     * permission; the caller gates on it.
     */
    Exception syncLocal(OfflineEngine.Report report) {
        List<BookEntry> phoneBooks = phoneSyncedBooks();

        try {
            // NOTE: pass the full phone-synced set at once; reconcile
            // purges the Android accounts of books no longer mirrored.
            Accounts.reconcile(context, phoneBooks);

            OfflineEngine engine = engine(null);
            for (BookEntry entry : phoneBooks) {
                bookStarted(entry);
                engine.syncPhone(entry.book.url, report);
            }
        } catch (Exception error) {
            return error;
        }

        return null;
    }

    /** The subscribed books set to mirror into the phone's contacts. */
    List<BookEntry> phoneSyncedBooks() {
        List<BookEntry> phone = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (entry.phoneSynced) {
                phone.add(entry);
            }
        }
        return phone;
    }

    /**
     * One account's engine pass: every one of its subscribed books
     * reconciles through io-offline (spine sync, body hydration,
     * conflict resolution), sharing one driver so the account-level
     * backends list their cards once per pass.
     */
    private void syncAccount(Account account, List<BookEntry> books, Outcome outcome)
            throws Exception {
        OfflineEngine engine = engine(account);

        for (BookEntry entry : books) {
            bookStarted(entry);
            outcome.absorb(engine.syncBook(entry.book.url, entry.remoteSynced));
            outcome.local |= entry.phoneSynced;
        }
    }

    /** An engine wired to the observer's progress display. */
    private OfflineEngine engine(Account account) {
        OfflineEngine engine = new OfflineEngine(base, client, account, context);
        if (observer != null) {
            engine.progress = observer::step;
        }
        return engine;
    }

    /**
     * Refreshes an OAuth account's access token and re-persists the
     * account, returning the fresh credentials. Providers may rotate
     * the refresh token, so a reissued one replaces the stored one.
     */
    private Account refresh(AccountEntry entry) {
        OauthTokens tokens =
                client.oauthRefresh(
                        entry.tokenEndpoint,
                        entry.clientId,
                        entry.clientSecret,
                        entry.refreshToken,
                        null);

        String refreshToken =
                tokens.refreshToken != null ? tokens.refreshToken : entry.refreshToken;
        Account fresh = new Account(entry.account.baseUrl, "", tokens.accessToken);
        AccountEntry updated =
                new AccountEntry(
                        fresh,
                        entry.email,
                        refreshToken,
                        entry.tokenEndpoint,
                        entry.clientId,
                        entry.clientSecret);

        store.add(updated);
        if (observer != null) {
            observer.accountRefreshed(updated);
        }
        return fresh;
    }

    /** The stored account entry matching an email, or null. */
    private AccountEntry entryFor(String email) {
        for (AccountEntry entry : store.loadAll()) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    private void bookStarted(BookEntry book) {
        if (observer != null) {
            observer.bookStarted(book);
        }
    }

    /** True for an HTTP 401 from any backend (expired or revoked token). */
    private static boolean expiredToken(Exception error) {
        return error instanceof CardamumException
                && Integer.valueOf(401).equals(((CardamumException) error).status);
    }
}
