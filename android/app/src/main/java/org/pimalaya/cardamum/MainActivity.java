package org.pimalaya.cardamum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.Provider;

/**
 * Single-activity host walking the app's screens through a ViewFlipper.
 *
 * <p>First launch is the connection flow: email, provider detection and
 * RFC 6764 discovery, proposed configurations, the password and a
 * connection check, then the addressbook selection; each selected
 * addressbook gets its own Android account and the account lands in the
 * Keystore. Every subsequent launch goes through the sync screen, which
 * fetches every selected addressbook into the SQLite base and projects
 * it into the phone's contacts, and lands on the contacts screen, where
 * vCards are listed, created, edited and deleted directly against the
 * CardDAV server.
 */
public class MainActivity extends Activity {
    private static final int PANEL_EMAIL = 0;
    private static final int PANEL_CONFIG = 1;
    private static final int PANEL_PASSWORD = 2;
    private static final int PANEL_BOOKS = 3;
    private static final int PANEL_SYNC = 4;
    private static final int PANEL_CONTACTS = 5;
    private static final int PANEL_CONTACT = 6;

    private static final int REQUEST_CONTACTS = 1;

    private static final int MENU_SYNC_REMOTE = 1;
    private static final int MENU_SYNC_LOCAL = 2;

    /** One contacts-list entry: a card and the addressbook it lives in. */
    private static final class Entry {
        final Addressbook book;
        final Card card;

        Entry(Addressbook book, Card card) {
            this.book = book;
            this.card = card;
        }
    }

    private final CardamumClient client = new CardamumClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private SecureStore store;
    private CardStore base;
    private ViewFlipper flipper;
    private ContactForm form;

    private Account account;

    /** Onboarding state: the entered email and its discovered context root. */
    private String pendingEmail;
    private String discoveredUrl;

    /** Addressbooks fetched at connection, awaiting the user's selection. */
    private List<Addressbook> pendingBooks = new ArrayList<>();

    /** The synced addressbooks (the user's selection). */
    private List<Addressbook> books = new ArrayList<>();

    private List<Entry> contacts = new ArrayList<>();

    /** Multi-select state on the contacts list, keyed by book url + id. */
    private boolean selectionMode;
    private final java.util.Set<String> selectedKeys = new java.util.HashSet<>();

    /** The card open in the editor (null card means composing a new one). */
    private Addressbook editingBook;
    private Card editingCard;

    /** The vCard the editor patches: the card's, or a fresh template. */
    private String editingVcard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new SecureStore(this);
        base = new CardStore(this);
        flipper = findViewById(R.id.flipper);
        form = new ContactForm(this);

        setUpEmailPanel();
        setUpPasswordPanel();
        setUpBooksPanel();
        setUpContactsPanel();
        setUpContactPanel();

        account = store.load();
        if (account == null) {
            show(PANEL_EMAIL);
        } else if (base.loadAddressbooks().isEmpty()) {
            // Fresh start: fetch the addressbooks for the selection step.
            show(PANEL_SYNC);
            syncRemote();
        } else {
            // Offline first: the store renders instantly, syncs are
            // manual (the Sync menu on the contacts screen).
            reloadFromBase();
            show(PANEL_CONTACTS);

            // NOTE: adb-only hooks, so syncs can be driven headlessly:
            // am start ... --ez syncRemote true / --ez syncLocal true
            if (getIntent().getBooleanExtra("syncRemote", false)) {
                syncRemote();
            } else if (getIntent().getBooleanExtra("syncLocal", false)) {
                syncLocal();
            }
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() == PANEL_CONTACTS && selectionMode) {
            exitSelection();
            return;
        }

        switch (flipper.getDisplayedChild()) {
            case PANEL_CONFIG:
                show(PANEL_EMAIL);
                break;
            case PANEL_PASSWORD:
                show(PANEL_CONFIG);
                break;
            case PANEL_CONTACT:
                show(PANEL_CONTACTS);
                break;
            default:
                super.onBackPressed();
        }
    }

    // ---- Connection screen --------------------------------------------------

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.email_submit)
                .setOnClickListener(
                        view -> {
                            String address = email.getText().toString().trim();
                            if (address.isEmpty() || !address.contains("@")) {
                                toast(getString(R.string.email_invalid));
                                return;
                            }

                            pendingEmail = address;
                            if (Provider.detect(address) == Provider.OTHER) {
                                discover();
                            } else {
                                showConfigs(Provider.detect(address));
                            }
                        });
    }

    /** Resolves the CardDAV context root for the email, then proposes configs. */
    private void discover() {
        Button submit = findViewById(R.id.email_submit);
        ProgressBar progress = findViewById(R.id.email_progress);
        submit.setEnabled(false);
        progress.setVisibility(View.VISIBLE);

        String resolver = dnsResolver();
        io.execute(
                () -> {
                    try {
                        String url = client.discover(pendingEmail, resolver);
                        main.post(
                                () -> {
                                    submit.setEnabled(true);
                                    progress.setVisibility(View.GONE);
                                    discoveredUrl = url;
                                    showConfigs(Provider.OTHER);
                                });
                    } catch (Exception error) {
                        main.post(
                                () -> {
                                    submit.setEnabled(true);
                                    progress.setVisibility(View.GONE);
                                    toast(message(error, R.string.discover_failed));
                                });
                    }
                });
    }

    /**
     * The active network's first usable DNS server as a tcp:// URL, or
     * null. Discovery prefers it over a public resolver: mobile
     * networks routinely block outbound DNS to third parties.
     */
    private String dnsResolver() {
        ConnectivityManager connectivity =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network network = connectivity.getActiveNetwork();
        LinkProperties link = network == null ? null : connectivity.getLinkProperties(network);
        if (link == null) {
            return null;
        }

        for (InetAddress dns : link.getDnsServers()) {
            String host = dns.getHostAddress();
            // Scoped link-local addresses (fe80::1%wlan0) do not fit in
            // a URL.
            if (host == null || host.contains("%")) {
                continue;
            }
            boolean bracketed = dns instanceof Inet6Address;
            return "tcp://" + (bracketed ? "[" + host + "]" : host) + ":53";
        }
        return null;
    }

    /** Fills the config screen with the proposals matching the provider. */
    private void showConfigs(Provider provider) {
        TextView email = findViewById(R.id.config_email);
        email.setText(pendingEmail);

        LinearLayout container = findViewById(R.id.config_container);
        container.removeAllViews();

        switch (provider) {
            case GOOGLE:
                container.addView(
                        configRow(
                                getString(R.string.config_google_api),
                                getString(R.string.config_soon),
                                null));
                container.addView(
                        configRow(
                                getString(R.string.config_google_carddav),
                                getString(R.string.config_soon),
                                null));
                break;
            case MICROSOFT:
                container.addView(
                        configRow(
                                getString(R.string.config_msgraph),
                                getString(R.string.config_soon),
                                null));
                break;
            case OTHER:
                container.addView(
                        configRow(
                                getString(R.string.config_carddav),
                                hostOf(discoveredUrl),
                                view -> openPasswordPanel()));
                container.addView(
                        configRow(
                                getString(R.string.config_jmap),
                                getString(R.string.config_soon),
                                null));
                break;
        }

        show(PANEL_CONFIG);
    }

    private View configRow(String title, String subtitle, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(14), dp(8), dp(14));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        row.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextColor(getResources().getColor(android.R.color.darker_gray, getTheme()));
        row.addView(subtitleView);

        if (onClick == null) {
            row.setAlpha(0.4f);
        } else {
            row.setOnClickListener(onClick);
        }

        return row;
    }

    private void openPasswordPanel() {
        TextView server = findViewById(R.id.password_server);
        server.setText(getString(R.string.password_server, pendingEmail, hostOf(discoveredUrl)));

        EditText password = findViewById(R.id.password_input);
        password.setText("");

        show(PANEL_PASSWORD);
    }

    private void setUpPasswordPanel() {
        findViewById(R.id.password_submit)
                .setOnClickListener(
                        view -> {
                            String password =
                                    ((EditText) findViewById(R.id.password_input))
                                            .getText()
                                            .toString();
                            if (password.isEmpty()) {
                                toast(getString(R.string.password_empty));
                                return;
                            }

                            connect(new Account(discoveredUrl, pendingEmail, password));
                        });
    }

    /** Verifies the account connects before persisting it and selecting books. */
    private void connect(Account candidate) {
        Button submit = findViewById(R.id.password_submit);
        ProgressBar progress = findViewById(R.id.password_progress);
        submit.setEnabled(false);
        progress.setVisibility(View.VISIBLE);

        io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched = client.listAddressbooks(candidate);
                        main.post(
                                () -> {
                                    submit.setEnabled(true);
                                    progress.setVisibility(View.GONE);
                                    account = candidate;
                                    store.save(candidate);
                                    showBookSelection(fetched);
                                });
                    } catch (Exception error) {
                        main.post(
                                () -> {
                                    submit.setEnabled(true);
                                    progress.setVisibility(View.GONE);
                                    toast(message(error, R.string.connect_failed));
                                });
                    }
                });
    }

    // ---- Addressbook selection screen -----------------------------------------

    /** Lists the addressbooks with checkboxes, all selected by default. */
    private void showBookSelection(List<Addressbook> fetched) {
        pendingBooks = fetched;

        LinearLayout container = findViewById(R.id.books_container);
        container.removeAllViews();

        for (Addressbook book : fetched) {
            CheckBox box = new CheckBox(this);
            box.setText(book.name);
            box.setChecked(true);
            box.setPadding(dp(8), dp(14), dp(8), dp(14));
            container.addView(box);
        }

        show(PANEL_BOOKS);
    }

    private void setUpBooksPanel() {
        findViewById(R.id.books_submit)
                .setOnClickListener(
                        view -> {
                            LinearLayout container = findViewById(R.id.books_container);
                            List<Addressbook> selected = new ArrayList<>();
                            for (int index = 0; index < container.getChildCount(); index++) {
                                if (((CheckBox) container.getChildAt(index)).isChecked()) {
                                    selected.add(pendingBooks.get(index));
                                }
                            }

                            if (selected.isEmpty()) {
                                toast(getString(R.string.books_empty_selection));
                                return;
                            }

                            books = selected;
                            base.replaceAddressbooks(selected);
                            show(PANEL_SYNC);

                            io.execute(
                                    () -> {
                                        try {
                                            Accounts.reconcile(this, account.login, selected);
                                        } catch (Exception error) {
                                            main.post(
                                                    () ->
                                                            toast(
                                                                    message(
                                                                            error,
                                                                            R.string
                                                                                    .accounts_failed)));
                                        }
                                        main.post(this::syncRemote);
                                    });
                        });
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == REQUEST_CONTACTS && hasContactsPermission()) {
            syncLocal();
        }
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Sync screen --------------------------------------------------------

    /**
     * Store-to-remote spoke: per addressbook, fetches the remote into
     * the store, pushes the staged local changes, and re-fetches the
     * pushed state. The phone is not touched; that is the local sync.
     */
    private void syncRemote() {
        show(PANEL_SYNC);
        TextView status = findViewById(R.id.sync_status);
        status.setText(R.string.sync_addressbooks);

        io.execute(
                () -> {
                    try {
                        List<Addressbook> selected = base.loadAddressbooks();

                        // Pre-selection state (fresh start): run the
                        // selection step first.
                        if (selected.isEmpty()) {
                            List<Addressbook> fetched = client.listAddressbooks(account);
                            main.post(
                                    () -> {
                                        if (fetched.isEmpty()) {
                                            status.setText(R.string.sync_no_addressbook);
                                        } else {
                                            showBookSelection(fetched);
                                        }
                                    });
                            return;
                        }

                        int conflicts = 0;
                        for (Addressbook book : selected) {
                            main.post(
                                    () ->
                                            status.setText(
                                                    getString(R.string.sync_cards, book.name)));

                            // Fetch first, so staged rows learn the
                            // server's resource names before the push
                            // addresses them.
                            List<Card> fetched = client.listCards(account, book.url);
                            base.replaceCards(book.url, fetched);

                            Map<String, String> serverEtags = new HashMap<>();
                            for (Card card : fetched) {
                                serverEtags.put(card.id, card.etag);
                            }

                            List<CardStore.Pending> pending = base.loadPending(book.url);
                            if (!pending.isEmpty()) {
                                main.post(
                                        () ->
                                                status.setText(
                                                        getString(
                                                                R.string.sync_push, book.name)));
                                conflicts += push(book, pending, serverEtags);

                                // The push changed the remote; re-fetch
                                // its resulting state.
                                base.replaceCards(book.url, client.listCards(account, book.url));
                            }
                        }

                        int unpushed = conflicts;
                        main.post(
                                () -> {
                                    reloadFromBase();
                                    show(PANEL_CONTACTS);
                                    if (unpushed > 0) {
                                        toast(getString(R.string.sync_conflicts, unpushed));
                                    }
                                });
                    } catch (Exception error) {
                        main.post(
                                () -> {
                                    // Offline fallback: the store of the
                                    // last sync keeps the app usable.
                                    if (base.loadAddressbooks().isEmpty()) {
                                        status.setText(message(error, R.string.sync_failed));
                                        return;
                                    }
                                    reloadFromBase();
                                    show(PANEL_CONTACTS);
                                    toast(message(error, R.string.sync_failed));
                                });
                    }
                });
    }

    /**
     * Store-to-phone spoke: reconciles the per-addressbook Android
     * accounts and hands the projection to Android's sync scheduler
     * (one manual expedited request per account; the work itself runs
     * in SyncService.onPerformSync, same path as the system's per
     * account "sync now"). Automatic sync stays off. Needs the contacts
     * permission, requested on first use.
     *
     * <p>NOTE: one-way for now; ingesting phone-side edits lands with
     * the io-offline engine, see docs/design.md.
     */
    private void syncLocal() {
        if (!hasContactsPermission()) {
            requestPermissions(
                    new String[] {
                        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                    },
                    REQUEST_CONTACTS);
            return;
        }

        io.execute(
                () -> {
                    List<Addressbook> selected = base.loadAddressbooks();

                    try {
                        Accounts.reconcile(this, account.login, selected);
                    } catch (Exception error) {
                        main.post(() -> toast(message(error, R.string.accounts_failed)));
                        return;
                    }

                    for (Addressbook book : selected) {
                        android.accounts.Account target = Accounts.find(this, book);
                        if (target != null) {
                            Bundle extras = new Bundle();
                            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                            extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                            ContentResolver.requestSync(
                                    target, ContactsContract.AUTHORITY, extras);
                        }
                    }

                    main.post(() -> toast(getString(R.string.sync_local_requested)));
                });
    }

    /**
     * Pushes the addressbook's staged local changes to the remote and
     * returns the number of conflicts. A 412 means the remote changed
     * under the staged edit: the change stays staged and retries next
     * sync (NOTE: keep-both resolution lands with the io-offline
     * engine). Any other failure aborts the sync (offline fallback).
     */
    private int push(Addressbook book, List<CardStore.Pending> changes,
            Map<String, String> serverEtags) {
        int conflicts = 0;

        for (CardStore.Pending pending : changes) {
            try {
                if (pending.deleted) {
                    client.deleteCard(account, book.url, pending.card);
                    base.removeCard(book.url, pending.card.id);
                } else if (pending.card.etag == null) {
                    base.confirmPush(
                            book.url,
                            client.createCard(
                                    account, book.url, pending.card.id, pending.card.vcard));
                } else {
                    base.confirmPush(book.url, client.updateCard(account, book.url, pending.card));
                }
            } catch (Exception error) {
                String message = error.getMessage();
                if (message != null && message.contains("HTTP 412")) {
                    // Server quirk (posteo's SabreDAV): some resources
                    // carry no internal ETag, so their If-Match always
                    // fails even though the listing serves one. The
                    // listing was fetched moments ago; when it still
                    // matches the staged base, the remote is provably
                    // unchanged and the write is safe without If-Match
                    // (the same guarantee, enforced by us instead).
                    String server = serverEtags.get(pending.card.id);
                    if (server != null && server.equals(pending.card.etag)) {
                        Log.w(
                                "cardamum",
                                "If-Match rejected but listing unchanged, retrying without it: "
                                        + pending.card.id);
                        Card unguarded =
                                new Card(
                                        pending.card.id,
                                        pending.card.uri,
                                        null,
                                        pending.card.vcard);
                        if (pending.deleted) {
                            client.deleteCard(account, book.url, unguarded);
                            base.removeCard(book.url, pending.card.id);
                        } else {
                            base.confirmPush(
                                    book.url, client.updateCard(account, book.url, unguarded));
                        }
                    } else {
                        conflicts++;
                        Log.w(
                                "cardamum",
                                "push conflict on "
                                        + pending.card.id
                                        + ": staged etag "
                                        + pending.card.etag
                                        + ", server etag "
                                        + server);
                    }
                } else {
                    throw error instanceof RuntimeException
                            ? (RuntimeException) error
                            : new IllegalStateException(error);
                }
            }
        }

        return conflicts;
    }

    // ---- Contacts screen ----------------------------------------------------

    private void setUpContactsPanel() {
        findViewById(R.id.contacts_sync)
                .setOnClickListener(
                        anchor -> {
                            PopupMenu menu = new PopupMenu(this, anchor);
                            menu.getMenu().add(0, MENU_SYNC_REMOTE, 0, R.string.sync_remote);
                            menu.getMenu().add(0, MENU_SYNC_LOCAL, 1, R.string.sync_local);
                            menu.setOnMenuItemClickListener(
                                    item -> {
                                        if (item.getItemId() == MENU_SYNC_REMOTE) {
                                            syncRemote();
                                        } else {
                                            syncLocal();
                                        }
                                        return true;
                                    });
                            menu.show();
                        });
        findViewById(R.id.contacts_add)
                .setOnClickListener(view -> openContact(books.get(0), null));
        findViewById(R.id.contacts_delete).setOnClickListener(view -> confirmDeleteSelected());
        findViewById(R.id.contacts_close).setOnClickListener(view -> exitSelection());
    }

    /** Renders the fetched cards into the contacts list, sorted by name. */
    private void renderContacts() {
        TextView status = findViewById(R.id.contacts_status);
        LinearLayout container = findViewById(R.id.contacts_container);
        TextView sticky = findViewById(R.id.contacts_sticky_letter);
        container.removeAllViews();
        updateSelectionUi();

        if (contacts.isEmpty()) {
            status.setText(R.string.contacts_empty);
            status.setVisibility(View.VISIBLE);
            sticky.setText("");
            return;
        }
        status.setVisibility(View.GONE);

        List<Entry> sorted = new ArrayList<>(contacts);
        sorted.sort(
                Comparator.comparing(
                        entry -> displayName(entry.card), String.CASE_INSENSITIVE_ORDER));
        for (Entry entry : sorted) {
            container.addView(contactRow(entry));
        }

        sticky.setText(letterOf(sorted.get(0).card));
        setUpStickyLetter(container, sticky);
    }

    /** The topmost visible row's letter drives the sticky gutter letter. */
    private void setUpStickyLetter(LinearLayout container, TextView sticky) {
        ScrollView scroll = findViewById(R.id.contacts_scroll);
        scroll.setOnScrollChangeListener(
                (view, x, y, oldX, oldY) -> {
                    for (int index = 0; index < container.getChildCount(); index++) {
                        View row = container.getChildAt(index);
                        if (row.getBottom() > y) {
                            Object letter = row.getTag();
                            if (letter != null) {
                                sticky.setText((String) letter);
                            }
                            return;
                        }
                    }
                });
    }

    private View contactRow(Entry entry) {
        String name = displayName(entry.card);
        String letter = letterOf(entry.card);
        String key = key(entry);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Even breathing room; the end keeps the checkbox column aligned
        // with the app-bar icons.
        row.setPadding(dp(12), dp(12), dp(8), dp(12));
        row.setTag(letter);
        // Ripple feedback on press (and long-press).
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setClickable(true);
        row.addView(avatar(name));

        TextView label = new TextView(this);
        label.setText(name);
        label.setPadding(dp(12), 0, 0, 0);
        label.setLayoutParams(
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(label);

        CheckBox check = new CheckBox(this);
        check.setClickable(false);
        check.setChecked(selectedKeys.contains(key));
        check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // A 48dp column whose box centres under the 48dp app-bar icons.
        check.setPadding(dp(12), 0, 0, 0);
        check.setLayoutParams(new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(check);

        row.setOnClickListener(
                view -> {
                    if (selectionMode) {
                        toggleSelection(key, check);
                    } else {
                        openContact(entry.book, entry.card);
                    }
                });
        row.setOnLongClickListener(
                view -> {
                    if (!selectionMode) {
                        selectionMode = true;
                    }
                    toggleSelection(key, check);
                    renderContacts();
                    return true;
                });
        return row;
    }

    private static String key(Entry entry) {
        return entry.book.url + " " + entry.card.id;
    }

    /** Toggles a row's selection and refreshes the app-bar affordances. */
    private void toggleSelection(String key, CheckBox check) {
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
            check.setChecked(false);
        } else {
            selectedKeys.add(key);
            check.setChecked(true);
        }
        if (selectedKeys.isEmpty()) {
            exitSelection();
        } else {
            updateSelectionUi();
        }
    }

    private void exitSelection() {
        selectionMode = false;
        selectedKeys.clear();
        renderContacts();
    }

    /** Title and app-bar icons reflect whether a selection is in progress. */
    private void updateSelectionUi() {
        TextView title = findViewById(R.id.contacts_title);
        title.setText(
                selectionMode
                        ? getString(R.string.selected_count, selectedKeys.size())
                        : getString(R.string.contacts_title));

        findViewById(R.id.contacts_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_sync).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_add).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    /** Confirms, then stages a delete for every selected contact. */
    private void confirmDeleteSelected() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_selected_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteSelected())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteSelected() {
        for (Entry entry : contacts) {
            if (selectedKeys.contains(key(entry))) {
                base.markDeleted(entry.book.url, entry.card);
            }
        }
        selectionMode = false;
        selectedKeys.clear();
        reloadFromBase();
    }

    /** A round avatar with the name's first letter, coloured by its hash. */
    private View avatar(String name) {
        int color = android.graphics.Color.HSVToColor(
                new float[] {Math.abs(name.hashCode()) % 360, 0.5f, 0.8f});
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color);

        TextView avatar = new TextView(this);
        avatar.setText(letter(name));
        avatar.setTextColor(0xFFFFFFFF);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(circle);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return avatar;
    }

    /** The uppercase index letter of a card, `#` when it has no letter. */
    private String letterOf(Card card) {
        return letter(displayName(card));
    }

    private static String letter(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "#";
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        return Character.isLetter(first) ? String.valueOf(first) : "#";
    }

    private int resolveAttr(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    // ---- Contact screen -----------------------------------------------------

    /** Opens the edit form on the card, or on a fresh template when null. */
    private void openContact(Addressbook book, Card card) {
        editingBook = book;
        editingCard = card;
        editingVcard = card == null ? newVcard() : card.vcard;

        TextView title = findViewById(R.id.contact_title);
        title.setText(card == null ? getString(R.string.contact_new) : displayName(card));

        form.load(card == null ? null : client.projectCard(card), editingVcard);

        show(PANEL_CONTACT);
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_save).setOnClickListener(view -> saveContact());
        findViewById(R.id.contact_back).setOnClickListener(view -> show(PANEL_CONTACTS));
    }

    /**
     * Stages the edited contact in the base (offline first: nothing
     * touches the network until the next sync pushes it) and returns to
     * the list.
     */
    private void saveContact() {
        String birthday = form.birthday();
        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            toast(getString(R.string.invalid_birthday));
            return;
        }

        try {
            String vcard = client.applyCard(editingVcard, form.collect());
            String id = editingCard == null ? vcardUid(vcard) : editingCard.id;
            String uri = editingCard == null ? null : editingCard.uri;
            String etag = editingCard == null ? null : editingCard.etag;
            base.saveLocal(editingBook.url, new Card(id, uri, etag, vcard));
        } catch (Exception error) {
            toast(message(error, R.string.save_failed));
            return;
        }

        reloadFromBase();
        show(PANEL_CONTACTS);
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    private void reloadFromBase() {
        books = base.loadAddressbooks();
        List<Entry> entries = new ArrayList<>();
        for (Addressbook book : books) {
            for (Card card : base.loadCards(book.url)) {
                entries.add(new Entry(book, card));
            }
        }
        contacts = entries;
        renderContacts();
    }

    // ---- vCard helpers ------------------------------------------------------

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /** The card's formatted name, falling back to its id. */
    private String displayName(Card card) {
        String name = vcardValue(card.vcard, "FN");
        return name == null || name.isEmpty() ? card.id : name;
    }

    /** The vCard's UID, falling back to a fresh one (used as resource name). */
    private static String vcardUid(String vcard) {
        String uid = vcardValue(vcard, "UID");
        return uid == null || uid.isEmpty() ? UUID.randomUUID().toString() : uid;
    }

    /** First value of the named property, parameters ignored, or null. */
    private static String vcardValue(String vcard, String name) {
        for (String line : unfold(vcard)) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }

            String property = line.substring(0, colon);
            int semicolon = property.indexOf(';');
            if (semicolon >= 0) {
                property = property.substring(0, semicolon);
            }

            if (property.equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Splits vCard text into logical lines, joining folded continuations. */
    private static List<String> unfold(String vcard) {
        List<String> lines = new ArrayList<>();
        for (String line : vcard.split("\r?\n")) {
            if (!lines.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + line.substring(1));
            } else {
                lines.add(line);
            }
        }
        return lines;
    }

    // ---- Utils --------------------------------------------------------------

    private void show(int panel) {
        flipper.setDisplayedChild(panel);
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }

    private String message(Exception error, int fallback) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? getString(fallback) : message;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
