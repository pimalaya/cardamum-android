package org.pimalaya.cardamum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
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
import android.widget.ListView;
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
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.OauthSession;
import org.pimalaya.cardamum.client.OauthTokens;
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
    private static final int PANEL_SETTINGS = 7;
    private static final int PANEL_HOME = 8;

    private static final int REQUEST_CONTACTS = 1;

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

    /** Every connected account (multi-account). */
    private final List<AccountEntry> accounts = new ArrayList<>();

    /** Onboarding state: the entered email and its discovered context root. */
    private String pendingEmail;
    private String discoveredUrl;

    /** True while the auth flow adds a further account (shows a back arrow). */
    private boolean addingAccount;

    /** OAuth grant in flight, between the browser redirect and its redemption. */
    private OauthSession pendingOauth;
    private String pendingTokenEndpoint;
    private String pendingBaseUrl;
    private String pendingAccountEmail;
    private String pendingClientId;

    /** Subscribed addressbooks grouped for the home screen. */
    private List<BookEntry> homeBooks = new ArrayList<>();

    /** Accounts collapsed on the home screen (all expanded by default). */
    private final java.util.Set<String> collapsedAccounts = new java.util.HashSet<>();

    /** The addressbook open in the contacts screen. */
    private BookEntry currentBook;

    /** The open addressbook's cards. */
    private List<Entry> contacts = new ArrayList<>();

    /** Checkboxes of the subscription editor, tagged with their book url. */
    private List<CheckBox> subscriptionBoxes = new ArrayList<>();

    /** The contacts list, sorted; backs the recycling adapter. */
    private List<Entry> sortedContacts = new ArrayList<>();
    private ContactsAdapter adapter;

    /** Lower-cased raw-vCard filter from the search bar; empty shows all. */
    private String searchQuery = "";

    /** Debounced search refresh, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

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
        setUpHomePanel();

        accounts.addAll(store.loadAll());
        if (accounts.isEmpty()) {
            // First run: the onboarding has no way back, at least one
            // account must be connected.
            startAuth(false);
        } else {
            // Offline first: the home renders instantly from the store,
            // syncs are manual (the Sync menu on the contacts screen).
            reloadHome();
            show(PANEL_HOME);

            // NOTE: adb-only hooks, so syncs can be driven headlessly:
            // am start ... --ez syncRemote true / --ez syncLocal true
            if (getIntent().getBooleanExtra("syncRemote", false)) {
                syncRemote(true);
            } else if (getIntent().getBooleanExtra("syncLocal", false)) {
                syncLocal();
            }
        }

        // An OAuth redirect can land here rather than in onNewIntent:
        // when the process died while the user was in the browser, the
        // redirect recreates the activity from scratch.
        handleOauthRedirect(getIntent());
    }

    /** Enters the connection flow, with a back arrow only when adding. */
    private void startAuth(boolean adding) {
        addingAccount = adding;
        pendingEmail = null;
        discoveredUrl = null;
        ((EditText) findViewById(R.id.email_input)).setText("");
        findViewById(R.id.email_back).setVisibility(adding ? View.VISIBLE : View.GONE);
        show(PANEL_EMAIL);
    }

    /** The account entry matching an email, or null. */
    private AccountEntry accountFor(String email) {
        for (AccountEntry entry : accounts) {
            if (entry.email.equals(email)) {
                return entry;
            }
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOauthRedirect(intent);
    }

    @Override
    public void onBackPressed() {
        if (flipper.getDisplayedChild() == PANEL_CONTACTS && selectionMode) {
            exitSelection();
            return;
        }

        switch (flipper.getDisplayedChild()) {
            case PANEL_EMAIL:
                // Only reachable with a back arrow when adding an account.
                if (addingAccount) {
                    show(PANEL_HOME);
                } else {
                    super.onBackPressed();
                }
                break;
            case PANEL_CONFIG:
                show(PANEL_EMAIL);
                break;
            case PANEL_PASSWORD:
                show(PANEL_CONFIG);
                break;
            case PANEL_BOOKS:
                show(PANEL_HOME);
                break;
            case PANEL_CONTACTS:
                goHome();
                break;
            case PANEL_CONTACT:
                show(PANEL_CONTACTS);
                break;
            default:
                super.onBackPressed();
        }
    }

    /** Refreshes the home listing from the store and shows it. */
    private void goHome() {
        reloadHome();
        show(PANEL_HOME);
    }

    // ---- Connection screen --------------------------------------------------

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.email_back).setOnClickListener(view -> show(PANEL_HOME));
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
                                    showError(error, R.string.discover_failed);
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
                                getString(R.string.config_oauth),
                                view -> startGoogleOauth()));
                break;
            case MICROSOFT:
                container.addView(
                        configRow(
                                getString(R.string.config_msgraph),
                                getString(R.string.config_oauth),
                                view -> startMicrosoftOauth()));
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

                            connect(
                                    new Account(discoveredUrl, pendingEmail, password),
                                    pendingEmail,
                                    null,
                                    null,
                                    null);
                        });
    }

    /**
     * Verifies the account connects before persisting it and selecting
     * books. The last three parameters carry the refresh material of an
     * OAuth account (all null for a password one), so expired access
     * tokens can be refreshed on later syncs.
     */
    private void connect(
            Account candidate,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId) {
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
                                    accounts.removeIf(entry -> entry.email.equals(email));
                                    AccountEntry entry =
                                            new AccountEntry(
                                                    candidate,
                                                    email,
                                                    refreshToken,
                                                    tokenEndpoint,
                                                    clientId);
                                    accounts.add(entry);
                                    store.add(entry);
                                    // Every addressbook of a new account is
                                    // subscribed by default; the filter edits it.
                                    base.replaceAddressbooks(email, fetched);
                                    syncRemote(true);
                                });
                    } catch (Exception error) {
                        Log.w("cardamum", "connect failed", error);
                        main.post(
                                () -> {
                                    submit.setEnabled(true);
                                    progress.setVisibility(View.GONE);
                                    showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    // ---- OAuth 2.0 sign-in -----------------------------------------------------

    /**
     * Starts the Google CardDAV OAuth grant: prepares the PKCE session,
     * then opens the authorization URL in the browser. The redirect
     * comes back through {@link #onNewIntent} on the reversed-client-id
     * custom scheme.
     */
    private void startGoogleOauth() {
        pendingOauth =
                new OauthSession(Oauth.GOOGLE_CLIENT_ID, Oauth.GOOGLE_REDIRECT_URI, Oauth.GOOGLE_SCOPE);
        pendingTokenEndpoint = Oauth.GOOGLE_TOKEN_ENDPOINT;
        pendingBaseUrl = Oauth.googleCardDavBase(pendingEmail);
        pendingAccountEmail = pendingEmail;
        pendingClientId = Oauth.GOOGLE_CLIENT_ID;

        // access_type=offline + prompt=consent make Google issue a
        // refresh token; login_hint preselects the entered account.
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            extras.put("login_hint", pendingEmail);
        } catch (JSONException ignored) {
            // A malformed extras object just omits the hints.
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.GOOGLE_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.GOOGLE_CLIENT_ID, Oauth.GOOGLE_REDIRECT_URI);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            pendingOauth = null;
            clearPendingOauth();
            showError(error, R.string.connect_failed);
        }
    }

    /** Name of the preferences holding the in-flight OAuth grant. */
    private static final String OAUTH_PREFS = "cardamum.oauth";

    /**
     * Persists the in-flight grant: the browser hop routinely outlives
     * the process (the system reclaims backgrounded apps), and without
     * the state and PKCE verifier the redirect could not be redeemed.
     */
    private void persistPendingOauth(String clientId, String redirectUri) {
        getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE)
                .edit()
                .putString("clientId", clientId)
                .putString("redirectUri", redirectUri)
                .putString("state", pendingOauth.state())
                .putString("verifier", pendingOauth.pkceVerifier())
                .putString("tokenEndpoint", pendingTokenEndpoint)
                .putString("baseUrl", pendingBaseUrl)
                .putString("email", pendingAccountEmail)
                .apply();
    }

    /** Restores the persisted grant into the pending fields, if any. */
    private void restorePendingOauth() {
        android.content.SharedPreferences prefs = getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE);
        String state = prefs.getString("state", null);
        if (state == null) {
            return;
        }

        pendingOauth =
                new OauthSession(
                        prefs.getString("clientId", ""),
                        prefs.getString("redirectUri", ""),
                        "",
                        state,
                        prefs.getString("verifier", ""));
        pendingTokenEndpoint = prefs.getString("tokenEndpoint", "");
        pendingBaseUrl = prefs.getString("baseUrl", "");
        pendingAccountEmail = prefs.getString("email", "");
        pendingClientId = prefs.getString("clientId", "");
    }

    /** Drops the persisted grant (redeemed, or aborted). */
    private void clearPendingOauth() {
        getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Starts the Microsoft Graph OAuth grant, mirroring the Google
     * one: PKCE session, browser, redirect through {@link #onNewIntent}
     * on the cardamum custom scheme. The connected account carries the
     * msgraph sentinel base URL, which routes every addressbook and
     * card operation through Graph instead of CardDAV.
     */
    private void startMicrosoftOauth() {
        pendingOauth =
                new OauthSession(
                        Oauth.MICROSOFT_CLIENT_ID,
                        Oauth.MICROSOFT_REDIRECT_URI,
                        Oauth.MICROSOFT_SCOPE);
        pendingTokenEndpoint = Oauth.MICROSOFT_TOKEN_ENDPOINT;
        pendingBaseUrl = Oauth.msgraphBase(pendingEmail);
        pendingAccountEmail = pendingEmail;
        pendingClientId = Oauth.MICROSOFT_CLIENT_ID;

        // login_hint preselects the entered account in the Microsoft
        // sign-in page; the refresh token comes from the offline_access
        // scope, no extra parameter needed.
        JSONObject extras = new JSONObject();
        try {
            extras.put("login_hint", pendingEmail);
        } catch (JSONException ignored) {
            // A malformed extras object just omits the hint.
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.MICROSOFT_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.MICROSOFT_CLIENT_ID, Oauth.MICROSOFT_REDIRECT_URI);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            pendingOauth = null;
            clearPendingOauth();
            showError(error, R.string.connect_failed);
        }
    }

    /**
     * Redeems the OAuth redirect for tokens and connects with them, the
     * access token standing in for the password (empty login, Bearer
     * auth). No-op unless a grant is in flight for this redirect,
     * restoring a persisted one when the process died while the user
     * was in the browser.
     */
    private void handleOauthRedirect(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            return;
        }
        if (pendingOauth == null) {
            restorePendingOauth();
        }
        if (pendingOauth == null) {
            return;
        }

        OauthSession session = pendingOauth;
        String tokenEndpoint = pendingTokenEndpoint;
        String baseUrl = pendingBaseUrl;
        String email = pendingAccountEmail;
        String clientId = pendingClientId;
        String redirectUrl = data.toString();
        pendingOauth = null;
        clearPendingOauth();

        io.execute(
                () -> {
                    try {
                        OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl);
                        main.post(
                                () ->
                                        connect(
                                                new Account(baseUrl, "", tokens.accessToken),
                                                email,
                                                tokens.refreshToken,
                                                tokenEndpoint,
                                                clientId));
                    } catch (Exception error) {
                        main.post(() -> showError(error, R.string.connect_failed));
                    }
                });
    }

    // ---- Home screen ----------------------------------------------------------

    private void setUpHomePanel() {
        findViewById(R.id.home_add).setOnClickListener(view -> startAuth(true));
        findViewById(R.id.home_filter).setOnClickListener(view -> openSubscriptions());

        // The add-account button sits on the accent; contrast its icon.
        int accent = resolveColor(android.R.attr.colorAccent);
        int onAccent = android.graphics.Color.luminance(accent) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
        ((android.widget.ImageButton) findViewById(R.id.home_add))
                .setImageTintList(android.content.res.ColorStateList.valueOf(onAccent));
    }

    /** Rebuilds the home listing: subscribed addressbooks grouped by account. */
    private void reloadHome() {
        homeBooks = base.loadSubscribedAddressbooks();

        LinearLayout container = findViewById(R.id.home_container);
        container.removeAllViews();

        if (homeBooks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.home_empty);
            empty.setTextColor(resolveColor(android.R.attr.textColorSecondary));
            empty.setPadding(dp(24), dp(24), dp(24), dp(24));
            container.addView(empty);
            return;
        }

        String group = null;
        LinearLayout books = null;
        for (BookEntry entry : homeBooks) {
            if (!entry.accountEmail.equals(group)) {
                group = entry.accountEmail;
                books = new LinearLayout(this);
                books.setOrientation(LinearLayout.VERTICAL);
                books.setVisibility(
                        collapsedAccounts.contains(group) ? View.GONE : View.VISIBLE);
                container.addView(accountHeader(group, books));
                container.addView(books);
            }

            TextView row = new TextView(this);
            row.setText(entry.book.name);
            row.setTextSize(14);
            row.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            row.setPadding(dp(14), dp(16), dp(14), dp(16));
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            row.setOnClickListener(view -> openBook(entry));
            books.addView(row);
        }
    }

    /**
     * A home account header collapsing its addressbooks on tap: the
     * email with a state chevron (down when expanded, right when
     * collapsed), toggling the books container's visibility.
     */
    private View accountHeader(String email, LinearLayout books) {
        TextView label = new TextView(this);
        label.setText(email);
        label.setTextSize(16);
        label.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        label.setLayoutParams(
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView chevron = new android.widget.ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setRotation(collapsedAccounts.contains(email) ? 0 : 90);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(16), dp(14), dp(16));
        header.setBackgroundColor(getColor(R.color.surface));
        header.setForeground(getDrawable(resolveAttr(android.R.attr.selectableItemBackground)));
        header.addView(label);
        header.addView(chevron);

        header.setOnClickListener(view -> {
            android.transition.TransitionManager.beginDelayedTransition(
                    findViewById(R.id.home_container));
            boolean collapse = books.getVisibility() == View.VISIBLE;
            if (collapse) {
                collapsedAccounts.add(email);
            } else {
                collapsedAccounts.remove(email);
            }
            books.setVisibility(collapse ? View.GONE : View.VISIBLE);
            chevron.setRotation(collapse ? 0 : 90);
        });

        return header;
    }

    /** Opens an addressbook's contacts, loading its cards from the store. */
    private void openBook(BookEntry entry) {
        currentBook = entry;
        searchQuery = "";
        closeSearch();
        exitSelection();

        contacts = new ArrayList<>();
        for (Card card : base.loadCards(entry.book.url)) {
            contacts.add(new Entry(entry.book, card));
        }

        ((TextView) findViewById(R.id.contacts_title)).setText(entry.book.name);
        renderContacts();
        show(PANEL_CONTACTS);
    }

    // ---- Subscription editor --------------------------------------------------

    /** Lists every addressbook (subscribed or not) with a checkbox to edit subscriptions. */
    private void openSubscriptions() {
        LinearLayout container = findViewById(R.id.books_container);
        container.removeAllViews();
        subscriptionBoxes = new ArrayList<>();

        String group = null;
        for (BookEntry entry : base.loadAllAddressbooks()) {
            if (!entry.accountEmail.equals(group)) {
                group = entry.accountEmail;
                TextView header = new TextView(this);
                header.setText(group);
                header.setTextColor(resolveColor(android.R.attr.textColorSecondary));
                header.setPadding(dp(8), dp(16), dp(8), dp(4));
                container.addView(header);
            }

            // Name left, checkbox on the right edge, matching the
            // contacts list rows; the whole row toggles.
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));

            TextView name = new TextView(this);
            name.setText(entry.book.name);
            name.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            name.setLayoutParams(
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(name);

            CheckBox box = new CheckBox(this);
            box.setChecked(entry.subscribed);
            box.setTag(entry.book.url);
            row.addView(box);

            row.setOnClickListener(view -> box.toggle());
            container.addView(row);
            subscriptionBoxes.add(box);
        }

        show(PANEL_BOOKS);
    }

    private void setUpBooksPanel() {
        findViewById(R.id.books_cancel).setOnClickListener(view -> show(PANEL_HOME));
        findViewById(R.id.books_submit)
                .setOnClickListener(
                        view -> {
                            java.util.Set<String> subscribed = new java.util.HashSet<>();
                            for (CheckBox box : subscriptionBoxes) {
                                if (box.isChecked()) {
                                    subscribed.add((String) box.getTag());
                                }
                            }
                            base.setSubscriptions(subscribed);
                            goHome();
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
    private void syncRemote(boolean toHome) {
        show(PANEL_SYNC);
        TextView status = findViewById(R.id.sync_status);
        status.setText(R.string.sync_addressbooks);

        io.execute(
                () -> {
                    int conflicts = 0;
                    Exception failure = null;

                    for (BookEntry entry : base.loadSubscribedAddressbooks()) {
                        AccountEntry account = accountFor(entry.accountEmail);
                        if (account == null) {
                            continue;
                        }

                        try {
                            conflicts += syncBook(account.account, entry, status);
                        } catch (Exception error) {
                            // An expired OAuth access token: refresh it
                            // and retry the addressbook once.
                            if (expiredToken(error) && account.refreshToken != null) {
                                try {
                                    conflicts += syncBook(refreshAccount(account), entry, status);
                                    continue;
                                } catch (Exception retryError) {
                                    error = retryError;
                                }
                            }

                            // One addressbook failing (revoked account,
                            // server down) must not block the others.
                            Log.w("cardamum", "sync failed for " + entry.book.name, error);
                            if (failure == null) {
                                failure = error;
                            }
                        }
                    }

                    int unpushed = conflicts;
                    Exception firstFailure = failure;
                    main.post(
                            () -> {
                                // Offline fallback: the store of the last
                                // sync keeps failing addressbooks usable.
                                finishSync(toHome);
                                if (firstFailure != null) {
                                    showError(firstFailure, R.string.sync_failed);
                                }
                                if (unpushed > 0) {
                                    toast(getString(R.string.sync_conflicts, unpushed));
                                }
                            });
                });
    }

    /** One addressbook's fetch-push-refetch cycle; returns push conflicts. */
    private int syncBook(Account acc, BookEntry entry, TextView status) {
        Addressbook book = entry.book;
        main.post(() -> status.setText(getString(R.string.sync_cards, book.name)));

        // Fetch first, so staged rows learn the server's resource names
        // before the push addresses them.
        List<Card> fetched = client.listCards(acc, book.url);
        base.replaceCards(book.url, fetched);

        Map<String, String> serverEtags = new HashMap<>();
        for (Card card : fetched) {
            serverEtags.put(card.id, card.etag);
        }

        List<CardStore.Pending> pending = base.loadPending(book.url);
        int conflicts = 0;
        if (!pending.isEmpty()) {
            main.post(() -> status.setText(getString(R.string.sync_push, book.name)));
            conflicts = push(acc, book, pending, serverEtags);

            // The push changed the remote; re-fetch its resulting state.
            base.replaceCards(book.url, client.listCards(acc, book.url));
        }
        return conflicts;
    }

    /**
     * Refreshes an OAuth account's access token and re-persists the
     * account, returning the fresh credentials. Providers may rotate
     * the refresh token, so a reissued one replaces the stored one.
     */
    private Account refreshAccount(AccountEntry entry) {
        OauthTokens tokens =
                client.oauthRefresh(entry.tokenEndpoint, entry.clientId, entry.refreshToken, null);

        String refreshToken =
                tokens.refreshToken != null ? tokens.refreshToken : entry.refreshToken;
        Account fresh = new Account(entry.account.baseUrl, "", tokens.accessToken);
        AccountEntry updated =
                new AccountEntry(
                        fresh, entry.email, refreshToken, entry.tokenEndpoint, entry.clientId);

        accounts.removeIf(candidate -> candidate.email.equals(entry.email));
        accounts.add(updated);
        store.add(updated);
        return fresh;
    }

    /** True for an HTTP 401 from either backend (expired or revoked token). */
    private static boolean expiredToken(Exception error) {
        String message = error.getMessage();
        return message != null && message.contains("HTTP 401");
    }

    /** After a sync, returns to the home listing, or the open addressbook. */
    private void finishSync(boolean toHome) {
        reloadHome();
        if (toHome || currentBook == null) {
            show(PANEL_HOME);
        } else {
            openBook(currentBook);
        }
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
                    List<BookEntry> subscribed = base.loadSubscribedAddressbooks();

                    try {
                        // The full subscribed set at once: reconcile purges
                        // accounts no longer in it, across all accounts.
                        Accounts.reconcile(this, subscribed);
                    } catch (Exception error) {
                        main.post(() -> showError(error, R.string.accounts_failed));
                        return;
                    }

                    for (BookEntry entry : subscribed) {
                        android.accounts.Account target = Accounts.find(this, entry.book);
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
    private int push(Account account, Addressbook book, List<CardStore.Pending> changes,
            Map<String, String> serverEtags) {
        int conflicts = 0;

        for (CardStore.Pending pending : changes) {
            try {
                if (pending.deleted) {
                    client.deleteCard(account, book.url, pending.card);
                    base.removeCard(book.url, pending.card.id);
                } else if (pending.card.etag == null) {
                    // The staged row is addressed by its local id: Graph
                    // names created resources itself, so the confirmed
                    // row may land under a new (server-assigned) id.
                    base.confirmPush(
                            book.url,
                            pending.card.id,
                            client.createCard(
                                    account, book.url, pending.card.id, pending.card.vcard));
                } else {
                    base.confirmPush(
                            book.url,
                            pending.card.id,
                            client.updateCard(
                                    account, book.url, pending.card, pending.baseVcard));
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
                                    book.url,
                                    pending.card.id,
                                    client.updateCard(
                                            account, book.url, unguarded, pending.baseVcard));
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
        findViewById(R.id.contacts_sync).setOnClickListener(this::showSyncMenu);
        findViewById(R.id.contacts_back).setOnClickListener(view -> goHome());
        findViewById(R.id.contacts_add)
                .setOnClickListener(view -> openContact(currentBook.book, null));
        findViewById(R.id.contacts_delete).setOnClickListener(view -> confirmDeleteSelected());
        findViewById(R.id.contacts_close).setOnClickListener(view -> exitSelection());

        findViewById(R.id.contacts_search).setOnClickListener(view -> openSearch());
        findViewById(R.id.contacts_search_close).setOnClickListener(view -> closeSearch());
        ((EditText) findViewById(R.id.contacts_search_input))
                .addTextChangedListener(
                        new android.text.TextWatcher() {
                            @Override
                            public void beforeTextChanged(
                                    CharSequence s, int start, int count, int after) {}

                            @Override
                            public void onTextChanged(
                                    CharSequence s, int start, int before, int count) {}

                            @Override
                            public void afterTextChanged(android.text.Editable s) {
                                String query = s.toString().trim().toLowerCase();
                                if (pendingSearch != null) {
                                    main.removeCallbacks(pendingSearch);
                                }
                                pendingSearch =
                                        () -> {
                                            searchQuery = query;
                                            renderContacts();
                                        };
                                main.postDelayed(pendingSearch, 250);
                            }
                        });

        ListView list = findViewById(R.id.contacts_list);
        adapter = new ContactsAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    Entry entry = sortedContacts.get(position);
                    if (selectionMode) {
                        toggleSelection(key(entry));
                    } else {
                        openContact(entry.book, entry.card);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    selectionMode = true;
                    toggleSelection(key(sortedContacts.get(position)));
                    return true;
                });

        TextView sticky = findViewById(R.id.contacts_sticky_letter);
        list.setOnScrollListener(
                new android.widget.AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(android.widget.AbsListView v, int state) {}

                    @Override
                    public void onScroll(
                            android.widget.AbsListView v, int first, int count, int total) {
                        if (first < sortedContacts.size()) {
                            sticky.setText(letterOf(sortedContacts.get(first).card));
                        }
                    }
                });

        // The FAB sits on the accent; tint its icon to contrast it.
        int accent = resolveColor(android.R.attr.colorAccent);
        int onAccent = android.graphics.Color.luminance(accent) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
        ((android.widget.ImageButton) findViewById(R.id.contacts_add))
                .setImageTintList(android.content.res.ColorStateList.valueOf(onAccent));

    }

    /** Shows the two-line sync menu anchored to the sync button. */
    private void showSyncMenu(View anchor) {
        View content = getLayoutInflater().inflate(R.layout.menu_sync, null);
        android.widget.PopupWindow popup =
                new android.widget.PopupWindow(
                        content,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        content.findViewById(R.id.menu_sync_remote)
                .setOnClickListener(
                        view -> {
                            popup.dismiss();
                            syncRemote(false);
                        });
        content.findViewById(R.id.menu_sync_local)
                .setOnClickListener(
                        view -> {
                            popup.dismiss();
                            syncLocal();
                        });
        popup.showAsDropDown(anchor);
    }

    /** Sorts the open addressbook's cards (search-filtered) and refreshes the list. */
    private void renderContacts() {
        TextView status = findViewById(R.id.contacts_status);
        TextView sticky = findViewById(R.id.contacts_sticky_letter);
        updateSelectionUi();

        sortedContacts = new ArrayList<>();
        for (Entry entry : contacts) {
            if (!searchQuery.isEmpty()
                    && !entry.card.vcard.toLowerCase().contains(searchQuery)) {
                continue;
            }
            sortedContacts.add(entry);
        }
        sortedContacts.sort(
                Comparator.comparing(
                        entry -> displayName(entry.card), String.CASE_INSENSITIVE_ORDER));
        adapter.notifyDataSetChanged();

        boolean empty = sortedContacts.isEmpty();
        status.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            status.setText(R.string.contacts_empty);
        }
        sticky.setText(empty ? "" : letterOf(sortedContacts.get(0).card));
    }

    /** Recycling adapter for the contacts list. */
    private final class ContactsAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return sortedContacts.size();
        }

        @Override
        public Object getItem(int position) {
            return sortedContacts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row =
                    convertView != null
                            ? convertView
                            : getLayoutInflater().inflate(R.layout.item_contact, parent, false);

            Entry entry = sortedContacts.get(position);
            String name = displayName(entry.card);

            TextView avatar = row.findViewById(R.id.contact_avatar);
            avatar.setText(letter(name));
            avatar.setBackground(avatarCircle(name));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            bindLine(row.findViewById(R.id.contact_email), vcardValue(entry.card.vcard, "EMAIL"));
            bindLine(row.findViewById(R.id.contact_phone), vcardValue(entry.card.vcard, "TEL"));

            CheckBox check = row.findViewById(R.id.contact_check);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedKeys.contains(key(entry)));

            return row;
        }
    }

    private static String key(Entry entry) {
        return entry.book.url + "\0" + entry.card.id;
    }

    /** Toggles a contact's selection and refreshes the list and app bar. */
    private void toggleSelection(String key) {
        if (!selectedKeys.remove(key)) {
            selectedKeys.add(key);
        }
        if (selectedKeys.isEmpty()) {
            exitSelection();
        } else {
            updateSelectionUi();
            adapter.notifyDataSetChanged();
        }
    }

    private void exitSelection() {
        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        adapter.notifyDataSetChanged();
    }

    /** Reveals the search bar and focuses it, popping the keyboard. */
    private void openSearch() {
        findViewById(R.id.contacts_search_bar).setVisibility(View.VISIBLE);
        EditText input = findViewById(R.id.contacts_search_input);
        if (input.requestFocus()) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** Clears the filter, hides the search bar and dismisses the keyboard. */
    private void closeSearch() {
        // Dismiss the keyboard while the input still holds the focus:
        // hiding the bar first drops the focus and leaves the keyboard
        // up.
        hideKeyboard();
        ((EditText) findViewById(R.id.contacts_search_input)).setText("");
        findViewById(R.id.contacts_search_bar).setVisibility(View.GONE);
    }

    /** The addressbook name gives way to the selected count while selecting. */
    private void updateSelectionUi() {
        TextView title = findViewById(R.id.contacts_title);
        if (selectionMode) {
            title.setText(getString(R.string.selected_count, selectedKeys.size()));
        } else if (currentBook != null) {
            title.setText(currentBook.book.name);
        }

        findViewById(R.id.contacts_back).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_sync).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_search).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
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
        reloadCurrentBook();
    }

    /** A round avatar with the name's first letter, coloured by its hash. */
    /** A muted round avatar background, coloured from the name's hash. */
    private android.graphics.drawable.GradientDrawable avatarCircle(String name) {
        int color = android.graphics.Color.HSVToColor(
                new float[] {Math.abs(name.hashCode()) % 360, 0.4f, 0.55f});
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color);
        return circle;
    }

    /** Sets a diminished sub-line, hiding it when the value is empty. */
    private void bindLine(TextView view, String value) {
        if (value == null || value.isEmpty()) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
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
            showError(error, R.string.save_failed);
            return;
        }

        reloadCurrentBook();
        show(PANEL_CONTACTS);
    }

    /** Rebuilds the open addressbook's contacts from the base (staged edits included). */
    private void reloadCurrentBook() {
        if (currentBook == null) {
            return;
        }
        contacts = new ArrayList<>();
        for (Card card : base.loadCards(currentBook.book.url)) {
            contacts.add(new Entry(currentBook.book, card));
        }
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
        hideKeyboard();
        flipper.setDisplayedChild(panel);
    }

    /** Resolves a theme attribute to its referenced resource id. */
    private int resolveAttr(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    /** Resolves a theme colour attribute to an ARGB int. */
    private int resolveColor(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        if (value.resourceId != 0) {
            return getResources().getColor(value.resourceId, getTheme());
        }
        return value.data;
    }

    /** Dismisses the soft keyboard, e.g. when leaving the edit form. */
    private void hideKeyboard() {
        // Fall back to the flipper's window token when nothing holds
        // the focus (it is the same window either way).
        View focus = getCurrentFocus();
        View anchor = focus != null ? focus : flipper;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(anchor.getWindowToken(), 0);
        if (focus != null) {
            focus.clearFocus();
        }
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

    /**
     * Shows an error in a dismissible dialog rather than a toast: the
     * full (often long, e.g. a server body) message stays on screen and
     * scrolls until acknowledged.
     */
    private void showError(Exception error, int fallback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message(error, fallback))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
