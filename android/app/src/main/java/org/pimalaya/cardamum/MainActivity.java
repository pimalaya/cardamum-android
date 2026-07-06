package org.pimalaya.cardamum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import org.pimalaya.cardamum.client.AuthMethod;
import org.pimalaya.cardamum.client.Card;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.OauthSession;
import org.pimalaya.cardamum.client.OauthTokens;
import org.pimalaya.cardamum.client.Provider;
import org.pimalaya.cardamum.client.ServiceConfig;

/**
 * Single-activity host walking the app's screens through a ViewFlipper.
 *
 * <p>First launch is the connection flow: email, provider detection and
 * discovery, proposed configurations, the credentials and a connection
 * check, then the addressbook selection. Every subsequent launch lands
 * on the app's root: the merged contacts list across every subscribed
 * addressbook of every account, contact-first per docs/merged-view.md
 * (replicas sharing a vCard UID collapse into one row; storage and sync
 * stay strictly per-replica). Addressbooks live behind a management
 * screen and as an optional filter on the list; syncs are manual, from
 * the Sync menu.
 */
public class MainActivity extends Activity {
    private static final int PANEL_EMAIL = 0;
    private static final int PANEL_CONFIG = 1;
    private static final int PANEL_PASSWORD = 2;
    private static final int PANEL_BOOKS = 3;
    private static final int PANEL_CONTACTS = 4;
    private static final int PANEL_CONTACT = 5;
    private static final int PANEL_SETTINGS = 6;
    private static final int PANEL_HOME = 7;

    private static final int REQUEST_CONTACTS = 1;

    /**
     * One replica in the contacts pool: a card, the addressbook it
     * lives in, the account owning it and its write-time index
     * (docs/merged-view.md), so rendering never parses a vCard.
     */
    private static final class Entry {
        final Addressbook book;
        final String accountEmail;
        final Card card;
        final String name;
        final String email;
        final String phone;
        final String uid;
        final String hash;

        Entry(Addressbook book, String accountEmail, CardStore.Indexed indexed) {
            this.book = book;
            this.accountEmail = accountEmail;
            this.card = indexed.card;
            this.name = indexed.name;
            this.email = indexed.email;
            this.phone = indexed.phone;
            this.uid = indexed.uid;
            this.hash = indexed.hash;
        }
    }

    /**
     * One merged row: the replicas sharing a logical contact (same
     * vCard UID across accounts and addressbooks); a card without a
     * UID stays a singleton group.
     */
    private static final class Group {
        final List<Entry> replicas = new ArrayList<>();

        Entry primary() {
            return replicas.get(0);
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

    /** Onboarding state: the entered email and its searched service configs. */
    private String pendingEmail;
    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

    /** Action of the config option currently picked (null until one is). */
    private Runnable selectedConfig;

    /** True while the auth flow adds a further account (shows a back arrow). */
    private boolean addingAccount;

    /** OAuth grant in flight, between the browser redirect and its redemption. */
    private OauthSession pendingOauth;
    private String pendingTokenEndpoint;
    private String pendingBaseUrl;
    private String pendingAccountEmail;
    private String pendingClientId;
    private String pendingClientSecret;

    /** Loopback listener of a custom-client grant; closed once redeemed. */
    private java.net.ServerSocket loopbackServer;

    /** Email of the just-connected account, awaiting its book selection. */
    private String connectedEmail;

    /** Addressbook checkboxes of the post-connect selection, tagged with their url. */
    private List<CheckBox> bookBoxes = new ArrayList<>();

    /** Subscribed addressbooks grouped for the home screen. */
    private List<BookEntry> homeBooks = new ArrayList<>();

    /** Accounts collapsed on the home screen (all expanded by default). */
    private final java.util.Set<String> collapsedAccounts = new java.util.HashSet<>();

    /** The addressbook filtering the contacts screen; null shows all. */
    private BookEntry currentBook;

    /** The replica pool of the contacts screen (filtered or all). */
    private List<Entry> contacts = new ArrayList<>();

    /** The merged rows, sorted; backs the recycling adapter. */
    private List<Group> sortedContacts = new ArrayList<>();
    private ContactsAdapter adapter;

    /** Lower-cased raw-vCard filter from the search bar; empty shows all. */
    private String searchQuery = "";

    /** Debounced search refresh, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    /** Sync to re-run once the contacts permission is granted, if any. */
    private Runnable afterContactsPermission;

    /** Link exceptions of the merged view, reloaded on every render. */
    private java.util.Set<String> detachedRefs = new java.util.HashSet<>();

    private Map<String, String> links = new HashMap<>();

    /** Multi-select state on the contacts list, keyed by merged group. */
    private boolean selectionMode;
    private final java.util.Set<String> selectedKeys = new java.util.HashSet<>();

    /** The card open in the editor (null card means composing a new one). */
    private Addressbook editingBook;
    private String editingAccountEmail;
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
            // Offline first: the merged contacts root renders instantly
            // from the store, syncs are manual (the Sync menu).
            goHome();

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

    /**
     * Enters the connection flow. The email view's back arrow shows
     * only when adding a further account: the first-run onboarding
     * starts here with no account, so it has no way back. The later
     * steps (config, books) always keep their back arrow.
     */
    private void startAuth(boolean adding) {
        addingAccount = adding;
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        ((EditText) findViewById(R.id.email_input)).setText("");

        // The email back arrow and every step's cross only exist when an
        // account is already configured (adding); the first-run flow is
        // a forced, one-way path.
        int nav = adding ? View.VISIBLE : View.GONE;
        for (int id :
                new int[] {
                    R.id.email_back, R.id.email_cancel, R.id.config_cancel, R.id.books_cancel,
                }) {
            findViewById(id).setVisibility(nav);
        }

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
                    goHome();
                } else {
                    super.onBackPressed();
                }
                break;
            case PANEL_CONFIG:
                show(PANEL_EMAIL);
                break;
            case PANEL_BOOKS:
                // The account is already stored (all-subscribed) by the
                // time this step shows, so leaving it is safe.
                goHome();
                break;
            case PANEL_CONTACTS:
                // A filtered list backs out to the merged root, which
                // is the app's root itself.
                if (currentBook != null) {
                    goHome();
                } else {
                    super.onBackPressed();
                }
                break;
            case PANEL_CONTACT:
                show(PANEL_CONTACTS);
                break;
            case PANEL_HOME:
                goHome();
                break;
            default:
                super.onBackPressed();
        }
    }

    /** The app's root: the merged contacts list across every account. */
    private void goHome() {
        openAllContacts();
    }

    /** Refreshes the addressbooks management screen and shows it. */
    private void openBooksManager() {
        reloadHome();
        show(PANEL_HOME);
    }

    // ---- Connection screen --------------------------------------------------

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.email_back).setOnClickListener(view -> goHome());
        findViewById(R.id.email_cancel).setOnClickListener(view -> goHome());
        findViewById(R.id.config_back).setOnClickListener(view -> show(PANEL_EMAIL));
        findViewById(R.id.config_cancel).setOnClickListener(view -> goHome());
        findViewById(R.id.config_continue)
                .setOnClickListener(
                        view -> {
                            if (selectedConfig != null) {
                                selectedConfig.run();
                            }
                        });

        Button submit = findViewById(R.id.email_submit);
        // Continue stays disabled until the field holds a plausible
        // address, so an empty or malformed one is simply not
        // submittable (no toast).
        submit.setEnabled(false);
        email.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        String address = s.toString().trim();
                        submit.setEnabled(!address.isEmpty() && address.contains("@"));
                    }
                });

        submit.setOnClickListener(
                view -> {
                    // The panel stays visible while the search runs;
                    // drop the field's focus so the keyboard leaves.
                    hideKeyboard();

                    String address = email.getText().toString().trim();
                    pendingEmail = address;
                    if (Provider.detect(address) == Provider.OTHER) {
                        search();
                    } else {
                        showConfigs(Provider.detect(address));
                    }
                });
    }

    /**
     * Searches every pimconf mechanism for the email's CardDAV and
     * JMAP service configs, then proposes them. Only protocols the
     * server actually speaks end up on the config screen; a failing
     * search surfaces as an error dialog and stays on the email panel.
     */
    private void search() {
        setAuthLoading(R.id.email_submit, R.id.email_progress, true);

        io.execute(
                () -> {
                    List<ServiceConfig> configs = new ArrayList<>();
                    Exception failure = null;
                    try {
                        // A null resolver falls back to the bridge's
                        // DNS-over-HTTPS default, which works on mobile
                        // networks that block outbound DNS over TCP.
                        configs = client.searchAll(pendingEmail, null);
                    } catch (Exception error) {
                        // Failing mechanisms are already skipped inside
                        // the search; this is the whole search dying.
                        Log.w("cardamum", "config search failed", error);
                        failure = error;
                    }

                    List<ServiceConfig> found = configs;
                    Exception searchFailure = failure;
                    main.post(
                            () -> {
                                setAuthLoading(R.id.email_submit, R.id.email_progress, false);
                                if (searchFailure != null) {
                                    showError(searchFailure, R.string.discover_failed);
                                    return;
                                }
                                searchedConfigs = found;
                                showConfigs(Provider.OTHER);
                            });
                });
    }

    /**
     * Toggles an auth Continue button between its label and an
     * in-button loader, the button disabled while loading.
     */
    private void setAuthLoading(int buttonId, int progressId, boolean loading) {
        Button button = findViewById(buttonId);
        button.setEnabled(!loading);
        button.setText(loading ? "" : getString(R.string.email_submit));
        findViewById(progressId).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** The config Continue back to idle: enabled once an option is picked. */
    private void resetConfigContinue() {
        setAuthLoading(R.id.config_continue, R.id.config_progress, false);
        findViewById(R.id.config_continue).setEnabled(selectedConfig != null);
    }

    /**
     * Fills the config screen with the proposals matching the provider,
     * as a radio list: one option per protocol and authentication
     * variant. Picking an option reveals the Continue button, which
     * runs it: OAuth starts the browser grant, password pops the
     * credentials dialog.
     */
    private void showConfigs(Provider provider) {
        TextView email = findViewById(R.id.config_email);
        email.setText(pendingEmail);

        selectedConfig = null;
        resetConfigContinue();

        LinearLayout container = findViewById(R.id.config_container);
        container.removeAllViews();

        switch (provider) {
            case GOOGLE:
                container.addView(
                        configItem(
                                getString(R.string.config_carddav),
                                null,
                                getString(R.string.config_oauth2_cardamum),
                                () ->
                                        startGoogleOauth(
                                                Oauth.GOOGLE_SCOPE,
                                                Oauth.googleCardDavBase(pendingEmail))));
                container.addView(
                        configItem(
                                getString(R.string.config_carddav),
                                null,
                                getString(R.string.config_oauth2_custom),
                                () ->
                                        promptCustomOauth(
                                                Oauth.googleCardDavBase(pendingEmail),
                                                Oauth.GOOGLE_AUTH_ENDPOINT,
                                                Oauth.GOOGLE_TOKEN_ENDPOINT,
                                                Oauth.GOOGLE_SCOPE)));
                container.addView(
                        configItem(
                                getString(R.string.config_google_api),
                                null,
                                getString(R.string.config_oauth2_cardamum),
                                () ->
                                        startGoogleOauth(
                                                Oauth.GOOGLE_PEOPLE_SCOPE,
                                                Oauth.googlePeopleBase(pendingEmail))));
                container.addView(
                        configItem(
                                getString(R.string.config_google_api),
                                null,
                                getString(R.string.config_oauth2_custom),
                                () ->
                                        promptCustomOauth(
                                                Oauth.googlePeopleBase(pendingEmail),
                                                Oauth.GOOGLE_AUTH_ENDPOINT,
                                                Oauth.GOOGLE_TOKEN_ENDPOINT,
                                                Oauth.GOOGLE_PEOPLE_SCOPE)));
                break;
            case MICROSOFT:
                container.addView(
                        configItem(
                                getString(R.string.config_msgraph),
                                null,
                                getString(R.string.config_oauth2_cardamum),
                                this::startMicrosoftOauth));
                container.addView(
                        configItem(
                                getString(R.string.config_msgraph),
                                null,
                                getString(R.string.config_oauth2_custom),
                                () ->
                                        promptCustomOauth(
                                                Oauth.msgraphBase(pendingEmail),
                                                Oauth.MICROSOFT_AUTH_ENDPOINT,
                                                Oauth.MICROSOFT_TOKEN_ENDPOINT,
                                                Oauth.MICROSOFT_SCOPE)));
                break;
            case OTHER:
                if (searchedConfigs.isEmpty()) {
                    container.addView(
                            configItem(getString(R.string.discover_failed), null, null, null));
                }
                for (ServiceConfig config : searchedConfigs) {
                    addConfigItems(container, config);
                }
                break;
        }

        show(PANEL_CONFIG);
    }

    /**
     * One radio option per authentication variant of a searched config:
     * password variants prompt for the credentials, OAuth code grants
     * prompt for a custom client (the endpoints prefilled from the
     * discovery), the rest stay disabled (no discovered client to use).
     */
    private void addConfigItems(LinearLayout container, ServiceConfig config) {
        String protocol = protocolName(config.service);
        String host = hostOf(config.url);
        String baseUrl =
                "jmap".equals(config.service)
                        ? CardamumClient.JMAP_PREFIX + config.url.replaceFirst("^https?://", "")
                        : config.url;

        for (AuthMethod method : config.auth) {
            switch (method.type) {
                case PASSWORD:
                    String login = config.username != null ? config.username : pendingEmail;
                    container.addView(
                            configItem(
                                    protocol,
                                    host,
                                    getString(R.string.config_password),
                                    () ->
                                            promptPassword(
                                                    baseUrl,
                                                    host,
                                                    login,
                                                    R.string.password_hint)));
                    break;
                case BEARER:
                    // Empty login: the secret is sent as a Bearer token
                    // instead of Basic credentials.
                    container.addView(
                            configItem(
                                    protocol,
                                    host,
                                    getString(R.string.config_token),
                                    () ->
                                            promptPassword(
                                                    baseUrl, host, "", R.string.config_token)));
                    break;
                case OAUTH_AUTHORIZATION_CODE_GRANT:
                    container.addView(
                            configItem(
                                    protocol,
                                    host,
                                    getString(R.string.config_oauth2_custom),
                                    () ->
                                            promptCustomOauth(
                                                    baseUrl,
                                                    method.authorizationEndpoint,
                                                    method.tokenEndpoint,
                                                    method.scope)));
                    break;
                default:
                    container.addView(
                            configItem(protocol, host, getString(R.string.config_oauth2), null));
            }
        }
    }

    /** The user-facing name of a searched service kind. */
    private String protocolName(String service) {
        switch (service) {
            case "carddav":
                return getString(R.string.config_carddav);
            case "jmap":
                return getString(R.string.config_jmap);
            default:
                return service;
        }
    }

    /**
     * A config option as a two-line radio: the protocol with its server
     * host in parentheses, then the authentication method diminished
     * below. Picking it arms the Continue button with `action`; a null
     * action renders the option disabled.
     */
    private View configItem(String title, String detail, String subtitle, Runnable action) {
        android.widget.RadioButton option = new android.widget.RadioButton(this);
        String main = detail == null ? title : title + " (" + detail + ")";

        if (subtitle == null) {
            option.setText(main);
        } else {
            android.text.SpannableString text =
                    new android.text.SpannableString(main + "\n" + subtitle);
            int start = main.length() + 1;
            text.setSpan(
                    new android.text.style.RelativeSizeSpan(0.8f),
                    start,
                    text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(
                    new android.text.style.ForegroundColorSpan(
                            resolveColor(android.R.attr.textColorSecondary)),
                    start,
                    text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            option.setText(text);
        }

        option.setTextSize(16);
        option.setPadding(dp(8), dp(12), dp(8), dp(12));

        if (action == null) {
            option.setEnabled(false);
        } else {
            option.setOnClickListener(
                    view -> {
                        selectedConfig = action;
                        findViewById(R.id.config_continue).setEnabled(true);
                    });
        }

        return option;
    }

    /**
     * Prompts for the secret in a dialog (a password, or an API token
     * when the login is empty: the empty login makes the backends send
     * it as Bearer instead of Basic), then verifies and persists the
     * account.
     */
    private void promptPassword(String baseUrl, String host, String login, int hint) {
        EditText input = new EditText(this);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(hint);

        // Inset the field from the dialog edges (setView is flush).
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(24), dp(8), dp(24), 0);
        wrapper.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.password_title)
                .setMessage(
                        getString(
                                R.string.password_server,
                                login.isEmpty() ? pendingEmail : login,
                                host))
                .setView(wrapper)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String password = input.getText().toString();
                            if (password.isEmpty()) {
                                toast(getString(R.string.password_empty));
                                return;
                            }
                            connect(
                                    new Account(baseUrl, login, password),
                                    pendingEmail,
                                    null,
                                    null,
                                    null,
                                    null);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Verifies the account connects before persisting it and selecting
     * books. The last four parameters carry the refresh material of an
     * OAuth account (all null for a password one), so expired access
     * tokens can be refreshed on later syncs.
     */
    private void connect(
            Account candidate,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        setAuthLoading(R.id.config_continue, R.id.config_progress, true);

        io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched = client.listAddressbooks(candidate);
                        main.post(
                                () -> {
                                    resetConfigContinue();
                                    accounts.removeIf(entry -> entry.email.equals(email));
                                    AccountEntry entry =
                                            new AccountEntry(
                                                    candidate,
                                                    email,
                                                    refreshToken,
                                                    tokenEndpoint,
                                                    clientId,
                                                    clientSecret);
                                    accounts.add(entry);
                                    store.add(entry);
                                    // Stored all-subscribed; the selection
                                    // step trims the set before the first sync.
                                    base.replaceAddressbooks(email, fetched);
                                    openBooksSelection(email, fetched);
                                });
                    } catch (Exception error) {
                        Log.w("cardamum", "connect failed", error);
                        main.post(
                                () -> {
                                    resetConfigContinue();
                                    showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    // ---- Addressbook selection -------------------------------------------------

    private void setUpBooksPanel() {
        findViewById(R.id.books_back).setOnClickListener(view -> goHome());
        findViewById(R.id.books_cancel).setOnClickListener(view -> goHome());
        findViewById(R.id.books_continue).setOnClickListener(view -> confirmBooks());
    }

    /**
     * Lists the just-connected account's addressbooks with a checkbox
     * each (all checked), so the user picks which to sync before the
     * first sync runs. Same chrome as the config panel.
     */
    private void openBooksSelection(String email, List<Addressbook> books) {
        connectedEmail = email;
        bookBoxes = new ArrayList<>();

        LinearLayout container = findViewById(R.id.books_container);
        container.removeAllViews();

        if (books.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.books_none);
            empty.setTextColor(resolveColor(android.R.attr.textColorSecondary));
            empty.setPadding(0, dp(16), 0, dp(16));
            container.addView(empty);
        }

        for (Addressbook book : books) {
            CheckBox box = new CheckBox(this);
            box.setText(book.name);
            box.setTextSize(16);
            box.setChecked(true);
            box.setTag(book.url);
            box.setPadding(dp(8), dp(12), dp(8), dp(12));
            box.setOnClickListener(view -> updateBooksContinue());
            container.addView(box);
            bookBoxes.add(box);
        }

        updateBooksContinue();
        setAuthLoading(R.id.books_continue, R.id.books_progress, false);
        show(PANEL_BOOKS);
    }

    /** Continue is enabled only while at least one addressbook is checked. */
    private void updateBooksContinue() {
        boolean any = false;
        for (CheckBox box : bookBoxes) {
            if (box.isChecked()) {
                any = true;
                break;
            }
        }
        findViewById(R.id.books_continue).setEnabled(any);
    }

    /** Applies the checked subscriptions, then runs the account's first sync. */
    private void confirmBooks() {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (CheckBox box : bookBoxes) {
            if (box.isChecked()) {
                subscribed.add((String) box.getTag());
            }
        }
        base.setSubscriptions(subscribed);
        setAuthLoading(R.id.books_continue, R.id.books_progress, true);
        syncRemote(true);
    }

    // ---- OAuth 2.0 sign-in -----------------------------------------------------

    /**
     * Starts a Google OAuth grant (the scope picks the backend: CardDAV
     * against the given principal root, or the People API against a
     * google sentinel base URL): prepares the PKCE session, then opens
     * the authorization URL in the browser. The redirect comes back
     * through {@link #onNewIntent} on the reversed-client-id custom
     * scheme.
     */
    private void startGoogleOauth(String scope, String baseUrl) {
        pendingOauth =
                new OauthSession(Oauth.GOOGLE_CLIENT_ID, null, Oauth.GOOGLE_REDIRECT_URI, scope);
        pendingTokenEndpoint = Oauth.GOOGLE_TOKEN_ENDPOINT;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = pendingEmail;
        pendingClientId = Oauth.GOOGLE_CLIENT_ID;
        pendingClientSecret = null;

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
            setAuthLoading(R.id.config_continue, R.id.config_progress, true);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            pendingOauth = null;
            clearPendingOauth();
            resetConfigContinue();
            showError(error, R.string.connect_failed);
        }
    }

    /** Name of the preferences holding the app settings. */
    private static final String SETTINGS_PREFS = "cardamum.settings";

    /** Settings key of the default save addressbook (its URL). */
    private static final String DEFAULT_BOOK = "defaultBookUrl";

    /** The default save addressbook's URL, or null when unset. */
    private String defaultBookUrl() {
        return getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getString(DEFAULT_BOOK, null);
    }

    /** Remembers the default save addressbook for new contacts. */
    private void setDefaultBook(BookEntry entry) {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
                .edit()
                .putString(DEFAULT_BOOK, entry.book.url)
                .apply();
        toast(getString(R.string.default_set, entry.book.name));
        reloadHome();
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
                .putString("clientSecret", pendingClientSecret == null ? "" : pendingClientSecret)
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

        String secret = prefs.getString("clientSecret", "");
        pendingOauth =
                new OauthSession(
                        prefs.getString("clientId", ""),
                        secret,
                        prefs.getString("redirectUri", ""),
                        "",
                        state,
                        prefs.getString("verifier", ""));
        pendingTokenEndpoint = prefs.getString("tokenEndpoint", "");
        pendingBaseUrl = prefs.getString("baseUrl", "");
        pendingAccountEmail = prefs.getString("email", "");
        pendingClientId = prefs.getString("clientId", "");
        pendingClientSecret = secret.isEmpty() ? null : secret;
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
                        null,
                        Oauth.MICROSOFT_REDIRECT_URI,
                        Oauth.MICROSOFT_SCOPE);
        pendingTokenEndpoint = Oauth.MICROSOFT_TOKEN_ENDPOINT;
        pendingBaseUrl = Oauth.msgraphBase(pendingEmail);
        pendingAccountEmail = pendingEmail;
        pendingClientId = Oauth.MICROSOFT_CLIENT_ID;
        pendingClientSecret = null;

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
            setAuthLoading(R.id.config_continue, R.id.config_progress, true);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            pendingOauth = null;
            clearPendingOauth();
            resetConfigContinue();
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
        String clientSecret = pendingClientSecret;
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
                                                clientId,
                                                clientSecret));
                    } catch (Exception error) {
                        main.post(
                                () -> {
                                    resetConfigContinue();
                                    showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    // ---- Custom OAuth 2.0 client (loopback redirect) ---------------------------

    /**
     * Prompts for a custom OAuth client (the fields prefilled from what
     * discovery knows) and, on confirm, runs the authorization code
     * grant against a loopback redirect. Unlike the built-in clients,
     * a custom app's redirect is not one the OS routes back to us, so
     * the grant lands on a local HTTP listener instead of an intent.
     */
    private void promptCustomOauth(
            String baseUrl, String authEndpoint, String tokenEndpoint, String scope) {
        EditText clientId = customOauthField(R.string.oauth_client_id, "");
        EditText clientSecret = customOauthField(R.string.oauth_client_secret, "");
        EditText authField = customOauthField(R.string.oauth_authorization_endpoint, authEndpoint);
        EditText tokenField = customOauthField(R.string.oauth_token_endpoint, tokenEndpoint);
        EditText scopeField = customOauthField(R.string.oauth_scope, scope == null ? "" : scope);
        EditText portField = customOauthField(R.string.oauth_port, Integer.toString(freePort()));
        portField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        for (EditText field : new EditText[] {
            clientId, clientSecret, authField, tokenField, scopeField, portField
        }) {
            fields.addView(field);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);

        new AlertDialog.Builder(this)
                .setTitle(R.string.oauth_custom_title)
                .setView(scroll)
                .setPositiveButton(
                        R.string.email_submit,
                        (dialog, which) -> {
                            String id = clientId.getText().toString().trim();
                            if (id.isEmpty()) {
                                toast(getString(R.string.oauth_client_id_empty));
                                return;
                            }
                            int port;
                            try {
                                port = Integer.parseInt(portField.getText().toString().trim());
                            } catch (NumberFormatException error) {
                                toast(getString(R.string.oauth_port_invalid));
                                return;
                            }
                            startLoopbackOauth(
                                    id,
                                    clientSecret.getText().toString().trim(),
                                    authField.getText().toString().trim(),
                                    tokenField.getText().toString().trim(),
                                    scopeField.getText().toString().trim(),
                                    baseUrl,
                                    port);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** One labelled field of the custom-OAuth dialog. */
    private EditText customOauthField(int hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return field;
    }

    /** A free loopback TCP port, for the redirect prefill. */
    private int freePort() {
        try (java.net.ServerSocket probe =
                new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        } catch (Exception error) {
            // A sensible default in the ephemeral range; the bind at
            // grant time surfaces a clash as an error.
            return 8117;
        }
    }

    /**
     * Runs a custom-client grant: binds the loopback port, opens the
     * browser, and serves the single redirect the browser lands on,
     * reconstructing its URL to redeem for tokens. The listener lives
     * on its own thread until the redirect arrives or the wait times
     * out; it dies with the process (a custom grant is not persisted,
     * having no OS-routed redirect to resume through).
     */
    private void startLoopbackOauth(
            String clientId,
            String clientSecret,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String baseUrl,
            int port) {
        String secret = clientSecret.isEmpty() ? null : clientSecret;
        String redirectUri = "http://127.0.0.1:" + port;

        java.net.ServerSocket server;
        try {
            server = new java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(300_000);
        } catch (Exception error) {
            showError(error, R.string.oauth_port_taken);
            return;
        }
        loopbackServer = server;

        OauthSession session = new OauthSession(clientId, secret, redirectUri, scope);

        // login_hint preselects the account; access_type=offline +
        // prompt=consent make Google-style servers issue a refresh
        // token (ignored by servers that do not use them).
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            extras.put("login_hint", pendingEmail);
        } catch (JSONException ignored) {
            // A malformed extras object just omits the hints.
        }

        String email = pendingEmail;
        io.execute(
                () -> {
                    try {
                        String authUrl = session.authorizeUrl(authEndpoint, extras);
                        main.post(
                                () -> {
                                    setAuthLoading(
                                            R.id.config_continue, R.id.config_progress, true);
                                    startActivity(
                                            new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                                });

                        String redirectUrl = awaitLoopbackRedirect(server, redirectUri);
                        OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl);
                        main.post(
                                () ->
                                        connect(
                                                new Account(baseUrl, "", tokens.accessToken),
                                                email,
                                                tokens.refreshToken,
                                                tokenEndpoint,
                                                clientId,
                                                secret));
                    } catch (Exception error) {
                        Log.w("cardamum", "custom oauth failed", error);
                        main.post(
                                () -> {
                                    resetConfigContinue();
                                    showError(error, R.string.connect_failed);
                                });
                    } finally {
                        closeLoopback();
                    }
                });
    }

    /**
     * Accepts the one browser redirect on the loopback socket, answers
     * a close-this-tab page, and rebuilds the redirect URL from the
     * request target so it can be validated and redeemed.
     */
    private String awaitLoopbackRedirect(java.net.ServerSocket server, String redirectUri)
            throws java.io.IOException {
        try (java.net.Socket socket = server.accept()) {
            java.io.BufferedReader reader =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    socket.getInputStream(),
                                    java.nio.charset.StandardCharsets.UTF_8));
            String requestLine = reader.readLine();

            String body =
                    "<html><body style='font-family:sans-serif;text-align:center;"
                            + "margin-top:4em'>"
                            + getString(R.string.oauth_loopback_done)
                            + "</body></html>";
            String response =
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: text/html; charset=utf-8\r\n"
                            + "Content-Length: "
                            + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body;
            socket.getOutputStream()
                    .write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // "GET /?code=..&state=.. HTTP/1.1" -> the request target is
            // the path and query the browser hit.
            String target = "/";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    target = parts[1];
                }
            }
            return redirectUri + target;
        }
    }

    /** Closes the loopback listener, if one is open. */
    private void closeLoopback() {
        java.net.ServerSocket server = loopbackServer;
        loopbackServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (java.io.IOException ignored) {
                // Already closed or never bound.
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the browser without a redirect (the user
        // cancelled the grant) must not leave the config Continue stuck
        // on its loader; a real redirect re-enters loading right after.
        if (flipper != null && flipper.getDisplayedChild() == PANEL_CONFIG) {
            resetConfigContinue();
        }
    }

    // ---- Addressbooks management screen -----------------------------------------

    private void setUpHomePanel() {
        findViewById(R.id.home_back).setOnClickListener(view -> goHome());
        findViewById(R.id.home_add).setOnClickListener(view -> startAuth(true));
    }

    /**
     * Rebuilds the home listing: every addressbook grouped by account,
     * unsynced ones flagged with a crossed-sync icon. Long-pressing a
     * header offers to delete the account, long-pressing a book to
     * toggle its sync.
     */
    private void reloadHome() {
        homeBooks = base.loadAllAddressbooks();

        LinearLayout container = findViewById(R.id.home_container);
        container.removeAllViews();

        if (homeBooks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.home_empty);
            empty.setTextColor(resolveColor(android.R.attr.textColorSecondary));
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
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

            TextView name = new TextView(this);
            name.setText(
                    entry.book.url.equals(defaultBookUrl())
                            ? getString(R.string.default_marker, entry.book.name)
                            : entry.book.name);
            name.setTextSize(16);
            name.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            name.setLayoutParams(
                    new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // The trailing checkbox rides in a 48dp slot with a 4dp end
            // padding, so its centre lines up with the app-bar icon
            // column and the account-header chevron.
            row.setPadding(dp(16), 0, dp(4), 0);
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            row.addView(name);

            CheckBox sync = new CheckBox(this);
            sync.setChecked(entry.subscribed);
            sync.setOnClickListener(view -> confirmToggleSync(entry, sync));
            row.addView(iconSlot(sync));

            // An unsynced book has nothing to open: tapping it ticks
            // the checkbox and asks to sync instead.
            row.setOnClickListener(
                    view -> {
                        if (entry.subscribed) {
                            openBook(entry);
                        } else {
                            sync.setChecked(true);
                            confirmToggleSync(entry, sync);
                        }
                    });
            row.setOnLongClickListener(
                    view -> {
                        setDefaultBook(entry);
                        return true;
                    });
            books.addView(row);
        }
    }

    /**
     * Confirms the sync checkbox's fresh state (the tap already flipped
     * it visually), reverting the checkbox when declined.
     */
    private void confirmToggleSync(BookEntry entry, CheckBox sync) {
        int message =
                entry.subscribed ? R.string.book_unsync_confirm : R.string.book_sync_confirm;
        new AlertDialog.Builder(this)
                .setMessage(getString(message, entry.book.name))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> toggleSync(entry))
                .setNegativeButton(
                        android.R.string.cancel,
                        (dialog, which) -> sync.setChecked(entry.subscribed))
                .setOnCancelListener(dialog -> sync.setChecked(entry.subscribed))
                .show();
    }

    private void toggleSync(BookEntry entry) {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (BookEntry candidate : base.loadAllAddressbooks()) {
            boolean keep =
                    candidate.book.url.equals(entry.book.url)
                            ? !entry.subscribed
                            : candidate.subscribed;
            if (keep) {
                subscribed.add(candidate.book.url);
            }
        }
        base.setSubscriptions(subscribed);
        reloadHome();
    }

    /** Confirms, then removes the account and everything under it. */
    private void confirmDeleteAccount(String email) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_account_confirm, email))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> deleteAccount(email))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteAccount(String email) {
        store.remove(email);
        base.removeAccount(email);
        accounts.removeIf(entry -> entry.email.equals(email));
        collapsedAccounts.remove(email);
        reloadHome();

        // Reconciling against the remaining subscribed set purges the
        // deleted account's Android accounts (and their raw contacts).
        List<BookEntry> subscribed = base.loadSubscribedAddressbooks();
        io.execute(() -> {
            try {
                Accounts.reconcile(this, subscribed);
            } catch (Exception error) {
                Log.w("cardamum", "account purge failed for " + email + ": " + error);
            }
        });
    }

    /**
     * Centres a control in an app-bar-icon-sized slot, so trailing home
     * controls (the header chevron, the row checkbox) share the size
     * and centre column of the app bar's action icons.
     */
    private android.widget.FrameLayout iconSlot(View child) {
        android.widget.FrameLayout slot = new android.widget.FrameLayout(this);
        slot.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        slot.addView(
                child,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER));
        return slot;
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
        // The chevron rides in a 48dp slot with a 4dp end padding, so
        // its centre lines up with the app-bar icon column and the book
        // rows' checkboxes.
        header.setPadding(dp(16), 0, dp(4), 0);
        header.setBackgroundColor(getColor(R.color.surface));
        header.setForeground(getDrawable(resolveAttr(android.R.attr.selectableItemBackground)));
        header.addView(label);
        header.addView(iconSlot(chevron));

        header.setOnClickListener(view -> {
            boolean collapse = books.getVisibility() == View.VISIBLE;
            if (collapse) {
                collapsedAccounts.add(email);
            } else {
                collapsedAccounts.remove(email);
            }
            books.setVisibility(collapse ? View.GONE : View.VISIBLE);
            chevron.setRotation(collapse ? 0 : 90);
        });
        header.setOnLongClickListener(view -> {
            confirmDeleteAccount(email);
            return true;
        });

        return header;
    }

    /** Opens the contacts list filtered to one addressbook. */
    private void openBook(BookEntry entry) {
        openContacts(entry);
    }

    /** Opens the merged contacts list across every subscribed addressbook. */
    private void openAllContacts() {
        openContacts(null);
    }

    /** Opens the contacts list, filtered to `only` or merged when null. */
    private void openContacts(BookEntry only) {
        currentBook = only;
        searchQuery = "";
        closeSearch();
        exitSelection();

        contacts = loadEntries(only);
        renderContacts();
        show(PANEL_CONTACTS);
    }

    /**
     * The replica pool from the store: every subscribed addressbook's
     * cards (tagged with their book and account), or one addressbook's
     * when a filter is given.
     */
    private List<Entry> loadEntries(BookEntry only) {
        List<Entry> entries = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (only != null && !only.book.url.equals(entry.book.url)) {
                continue;
            }
            for (CardStore.Indexed indexed : base.loadIndexedCards(entry.book.url)) {
                entries.add(new Entry(entry.book, entry.accountEmail, indexed));
            }
        }
        return entries;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == REQUEST_CONTACTS && hasContactsPermission()) {
            Runnable retry = afterContactsPermission;
            afterContactsPermission = null;
            if (retry != null) {
                retry.run();
            }
        }
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Sync -----------------------------------------------------------------

    /**
     * Outcome of a remote sync pass: what was pulled from the server,
     * pushed to it and left merged (kept local on a conflict), plus the
     * first failure.
     */
    private static final class SyncOutcome {
        int pulled;
        int pushed;
        int merged;
        Exception failure;
    }

    /**
     * Toggles the contacts app-bar sync icon between the glyph and an
     * in-place spinner while a sync runs.
     */
    private void setSyncing(boolean syncing) {
        findViewById(R.id.contacts_sync).setVisibility(syncing ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_sync_progress)
                .setVisibility(syncing ? View.VISIBLE : View.GONE);
    }

    /**
     * Store-to-remote spoke: per addressbook, fetches the remote into
     * the store, pushes the staged local changes, and re-fetches the
     * pushed state. The phone is not touched; that is the local sync.
     * When `toHome`, this is the onboarding's first sync (the books
     * Continue button owns the spinner); otherwise it is a manual sync
     * from the contacts screen (the app-bar sync icon owns it).
     */
    private void syncRemote(boolean toHome) {
        if (!toHome) {
            setSyncing(true);
        }

        io.execute(
                () -> {
                    SyncOutcome outcome = runRemoteSync();
                    main.post(
                            () -> {
                                if (toHome) {
                                    finishSync(true);
                                } else {
                                    setSyncing(false);
                                    finishSync(false);
                                }
                                reportSync(outcome);
                            });
                });
    }

    /** The remote sync pass itself, off the main thread. */
    private SyncOutcome runRemoteSync() {
        SyncOutcome outcome = new SyncOutcome();

        Map<String, List<BookEntry>> byAccount = new java.util.LinkedHashMap<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            byAccount
                    .computeIfAbsent(entry.accountEmail, email -> new ArrayList<>())
                    .add(entry);
        }

        for (Map.Entry<String, List<BookEntry>> group : byAccount.entrySet()) {
            AccountEntry account = accountFor(group.getKey());
            if (account == null) {
                continue;
            }

            try {
                syncAccount(account.account, group.getKey(), group.getValue(), outcome);
            } catch (Exception error) {
                // An expired OAuth access token: refresh it and retry
                // the account once.
                if (expiredToken(error) && account.refreshToken != null) {
                    try {
                        syncAccount(
                                refreshAccount(account),
                                group.getKey(),
                                group.getValue(),
                                outcome);
                        continue;
                    } catch (Exception retryError) {
                        error = retryError;
                    }
                }

                // One account failing (revoked token, server down) must
                // not block the others.
                Log.w("cardamum", "sync failed for " + group.getKey(), error);
                if (outcome.failure == null) {
                    outcome.failure = error;
                }
            }
        }

        return outcome;
    }

    /** Surfaces a sync outcome: an error dialog, or a pull/push/merge toast. */
    private void reportSync(SyncOutcome outcome) {
        if (outcome.failure != null) {
            // Offline fallback: the store of the last sync keeps failing
            // addressbooks usable.
            showError(outcome.failure, R.string.sync_failed);
        } else {
            toast(
                    getString(
                            R.string.sync_done,
                            outcome.pulled,
                            outcome.pushed,
                            outcome.merged));
        }
    }

    /**
     * One account's fetch-push-refetch cycle: account-level backends
     * (JMAP, Google) list every card once with its m:n memberships;
     * per-collection backends (CardDAV, Graph) cycle each subscribed
     * addressbook.
     */
    private void syncAccount(
            Account acc, String email, List<BookEntry> books, SyncOutcome outcome) {
        if (CardamumClient.isAccountLevel(acc)) {
            Map<String, String> before = etagsOf(base.loadAccountCards(email));

            // Fetch first, so staged rows learn the server's resource
            // names before the push addresses them.
            List<CardStore.Row> rows = fetchAccountRows(acc, books);
            countPulled(before, rowCards(rows), outcome);
            base.replaceAccountCards(email, rows);

            boolean pushed = false;
            List<CardStore.Pending> pending = base.loadPending(email);
            if (!pending.isEmpty()) {
                push(acc, email, pending, etagsOf(rowCards(rows)), outcome);
                pushed = true;
            }

            // Membership changes push after the cards, so a created
            // replica's staged memberships address its server id (the
            // storage key rename of confirmPush carries them over).
            List<CardStore.MembershipChange> memberships = base.loadPendingMemberships(email);
            if (!memberships.isEmpty()) {
                pushMemberships(acc, email, memberships, outcome);
                pushed = true;
            }

            if (pushed) {
                // The push changed the remote; re-fetch its state.
                base.replaceAccountCards(email, fetchAccountRows(acc, books));
            }
        } else {
            for (BookEntry entry : books) {
                syncBook(acc, email, entry, outcome);
            }
        }
    }

    /**
     * The account-level fetch: every card with its memberships mapped
     * from book ids to the subscribed addressbook URLs. A card with no
     * subscribed membership is skipped: it neither displays nor stores.
     */
    private List<CardStore.Row> fetchAccountRows(Account acc, List<BookEntry> books) {
        Map<String, String> urlById = new HashMap<>();
        for (BookEntry entry : books) {
            urlById.put(entry.book.id, entry.book.url);
        }

        List<CardStore.Row> rows = new ArrayList<>();
        for (Card card : client.listAccountCards(acc)) {
            List<String> urls = new ArrayList<>();
            for (String bookId : card.books) {
                String url = urlById.get(bookId);
                if (url != null) {
                    urls.add(url);
                }
            }
            if (!urls.isEmpty()) {
                rows.add(new CardStore.Row(card.id, card, urls));
            }
        }
        return rows;
    }

    /**
     * One addressbook's fetch-push-refetch cycle (per-collection
     * backends), accumulating what it pulled, pushed and merged into
     * `outcome`.
     */
    private void syncBook(Account acc, String email, BookEntry entry, SyncOutcome outcome) {
        Addressbook book = entry.book;

        // The store as of the last sync, to count what the fetch brings.
        Map<String, String> before = etagsOf(base.loadCards(book.url));

        // Fetch first, so staged rows learn the server's resource names
        // before the push addresses them.
        List<Card> fetched = client.listCards(acc, book.url);
        countPulled(before, fetched, outcome);
        base.replaceBookCards(email, book.url, fetched);

        List<CardStore.Pending> pending = base.loadPendingForBook(email, book.url);
        if (!pending.isEmpty()) {
            push(acc, email, pending, etagsOf(fetched), outcome);

            // The push changed the remote; re-fetch its resulting state.
            base.replaceBookCards(email, book.url, client.listCards(acc, book.url));
        }
    }

    /** The cards keyed by id to their ETag. */
    private static Map<String, String> etagsOf(List<Card> cards) {
        Map<String, String> etags = new HashMap<>();
        for (Card card : cards) {
            etags.put(card.id, card.etag);
        }
        return etags;
    }

    /** Counts fetched-vs-known ETag differences (added, changed, removed). */
    private static void countPulled(
            Map<String, String> before, List<Card> fetched, SyncOutcome outcome) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Card card : fetched) {
            seen.add(card.id);
            if (!java.util.Objects.equals(before.get(card.id), card.etag)) {
                outcome.pulled++;
            }
        }
        for (String id : before.keySet()) {
            if (!seen.contains(id)) {
                outcome.pulled++;
            }
        }
    }

    private static List<Card> rowCards(List<CardStore.Row> rows) {
        List<Card> cards = new ArrayList<>(rows.size());
        for (CardStore.Row row : rows) {
            cards.add(row.card);
        }
        return cards;
    }

    /**
     * The replica's storage key (docs/merged-view.md): the bare server
     * id on account-level backends, the collection-scoped key on
     * per-collection ones.
     */
    private static String cardKey(Account account, String bookUrl, String id) {
        return CardamumClient.isAccountLevel(account) ? id : CardStore.key(bookUrl, id);
    }

    /**
     * Pushes staged membership changes (account-level backends): each
     * card's adds and removes batch into one client call, the
     * addressbook URLs mapped back to book ids.
     */
    private void pushMemberships(
            Account acc,
            String email,
            List<CardStore.MembershipChange> changes,
            SyncOutcome outcome) {
        Map<String, String> idByUrl = new HashMap<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            idByUrl.put(entry.book.url, entry.book.id);
        }

        Map<String, List<CardStore.MembershipChange>> byCard = new java.util.LinkedHashMap<>();
        for (CardStore.MembershipChange change : changes) {
            byCard.computeIfAbsent(change.key, key -> new ArrayList<>()).add(change);
        }

        for (List<CardStore.MembershipChange> card : byCard.values()) {
            List<String> add = new ArrayList<>();
            List<String> remove = new ArrayList<>();
            for (CardStore.MembershipChange change : card) {
                String bookId = idByUrl.get(change.bookUrl);
                if (bookId != null) {
                    (change.added ? add : remove).add(bookId);
                }
            }

            if (!add.isEmpty() || !remove.isEmpty()) {
                client.updateCardBooks(acc, card.get(0).cardId, add, remove);
                outcome.pushed++;
            }
            // NOTE: changes whose book vanished confirm without a push.
            for (CardStore.MembershipChange change : card) {
                base.confirmMembership(email, change.key, change.bookUrl, change.added);
            }
        }
    }

    /**
     * Refreshes an OAuth account's access token and re-persists the
     * account, returning the fresh credentials. Providers may rotate
     * the refresh token, so a reissued one replaces the stored one.
     */
    private Account refreshAccount(AccountEntry entry) {
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

    /** After a sync, lands on the merged root or refreshes the open list. */
    private void finishSync(boolean toHome) {
        if (toHome) {
            goHome();
        } else {
            reloadContacts();
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
        if (!ensureContactsPermission(this::syncLocal)) {
            return;
        }

        setSyncing(true);
        io.execute(
                () -> {
                    Exception failure = runLocalSync();
                    main.post(
                            () -> {
                                setSyncing(false);
                                if (failure != null) {
                                    showError(failure, R.string.accounts_failed);
                                } else {
                                    toast(getString(R.string.sync_local_done));
                                }
                            });
                });
    }

    /**
     * Full sync: the phone spoke, then the remote spoke, then the phone
     * spoke again (so remote changes reach the phone in one pass), per
     * the hub-and-spoke topology. One spinner spans the whole run.
     */
    private void syncFull() {
        if (!ensureContactsPermission(this::syncFull)) {
            return;
        }

        setSyncing(true);
        io.execute(
                () -> {
                    Exception localFailure = runLocalSync();
                    SyncOutcome remote = runRemoteSync();
                    if (localFailure == null) {
                        localFailure = runLocalSync();
                    }

                    Exception firstFailure =
                            localFailure != null ? localFailure : remote.failure;
                    main.post(
                            () -> {
                                setSyncing(false);
                                finishSync(false);
                                if (firstFailure != null) {
                                    showError(firstFailure, R.string.sync_failed);
                                } else {
                                    reportSync(remote);
                                }
                            });
                });
    }

    /**
     * The phone spoke: reconciles the per-addressbook Android accounts
     * and hands the projection to Android's sync scheduler (one manual
     * expedited request per account; the work itself runs in
     * SyncService.onPerformSync, same path as the system's per-account
     * "sync now"). Automatic sync stays off. Returns a reconcile
     * failure, or null.
     *
     * <p>NOTE: one-way for now; ingesting phone-side edits lands with
     * the io-offline engine, see docs/design.md.
     */
    private Exception runLocalSync() {
        List<BookEntry> subscribed = base.loadSubscribedAddressbooks();

        try {
            // The full subscribed set at once: reconcile purges accounts
            // no longer in it, across all accounts.
            Accounts.reconcile(this, subscribed);
        } catch (Exception error) {
            return error;
        }

        for (BookEntry entry : subscribed) {
            android.accounts.Account target = Accounts.find(this, entry.book);
            if (target != null) {
                Bundle extras = new Bundle();
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
                extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
                ContentResolver.requestSync(target, ContactsContract.AUTHORITY, extras);
            }
        }

        return null;
    }

    /**
     * Ensures the contacts permission before a phone-touching sync;
     * when it must be requested, remembers `retry` to run once granted
     * and returns false.
     */
    private boolean ensureContactsPermission(Runnable retry) {
        if (hasContactsPermission()) {
            return true;
        }
        afterContactsPermission = retry;
        requestPermissions(
                new String[] {
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                },
                REQUEST_CONTACTS);
        return false;
    }

    /**
     * Pushes staged local changes to the remote, accumulating the
     * pushed and merged counts into `outcome`. Each replica pushes
     * against its collection (the first membership URL; per-collection
     * backends have exactly one, account-level backends only need it
     * for creates). A 412 means the remote changed under the staged
     * edit: the change stays staged (merged) and retries next sync
     * (NOTE: keep-both resolution lands with the io-offline engine).
     * Any other failure aborts the sync (offline fallback).
     */
    private void push(Account account, String email, List<CardStore.Pending> changes,
            Map<String, String> serverEtags, SyncOutcome outcome) {
        for (CardStore.Pending pending : changes) {
            String bookUrl = pending.books.isEmpty() ? null : pending.books.get(0);
            if (bookUrl == null) {
                continue;
            }

            try {
                if (pending.deleted) {
                    client.deleteCard(account, bookUrl, pending.card);
                    base.removeCard(email, pending.key);
                } else if (pending.card.etag == null) {
                    // The staged row is addressed by its local key: the
                    // server may name created resources itself (Graph,
                    // JMAP, Google assign ids), so the confirmed row may
                    // land under a new key.
                    Card created =
                            client.createCard(
                                    account, bookUrl, pending.card.id, pending.card.vcard);
                    base.confirmPush(
                            email, pending.key, cardKey(account, bookUrl, created.id), created);
                } else {
                    Card updated =
                            client.updateCard(account, bookUrl, pending.card, pending.baseVcard);
                    base.confirmPush(
                            email, pending.key, cardKey(account, bookUrl, updated.id), updated);
                }
                outcome.pushed++;
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
                            client.deleteCard(account, bookUrl, unguarded);
                            base.removeCard(email, pending.key);
                        } else {
                            Card updated =
                                    client.updateCard(
                                            account, bookUrl, unguarded, pending.baseVcard);
                            base.confirmPush(
                                    email,
                                    pending.key,
                                    cardKey(account, bookUrl, updated.id),
                                    updated);
                        }
                        outcome.pushed++;
                    } else {
                        outcome.merged++;
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
    }

    // ---- Contacts screen ----------------------------------------------------

    private void setUpContactsPanel() {
        findViewById(R.id.contacts_sync).setOnClickListener(this::showSyncMenu);
        findViewById(R.id.contacts_back).setOnClickListener(view -> goHome());
        findViewById(R.id.contacts_books).setOnClickListener(view -> openBooksManager());
        findViewById(R.id.contacts_add).setOnClickListener(view -> addContact());
        findViewById(R.id.contacts_link).setOnClickListener(view -> linkSelected());
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
                    Group group = sortedContacts.get(position);
                    if (selectionMode) {
                        toggleSelection(groupKey(group));
                    } else {
                        openGroup(group);
                    }
                });
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    selectionMode = true;
                    toggleSelection(groupKey(sortedContacts.get(position)));
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
                            sticky.setText(letter(displayName(sortedContacts.get(first).primary())));
                        }
                    }
                });

    }

    /**
     * Opens a merged row: straight into the editor for a single
     * replica, through an account chooser when the contact lives in
     * several places (editing is strictly per-replica, one vCard
     * document at a time).
     */
    private void openGroup(Group group) {
        if (group.replicas.size() == 1) {
            Entry entry = group.primary();
            openContact(entry.book, entry.accountEmail, entry.card);
            return;
        }

        String[] labels = new String[group.replicas.size()];
        for (int index = 0; index < labels.length; index++) {
            Entry entry = group.replicas.get(index);
            labels[index] = entry.book.name + " (" + entry.accountEmail + ")";
        }

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(displayName(group.primary()))
                        .setItems(
                                labels,
                                (dialog, which) -> {
                                    Entry entry = group.replicas.get(which);
                                    openContact(entry.book, entry.accountEmail, entry.card);
                                })
                        .setNeutralButton(R.string.unlink, (dialog, which) -> unlinkGroup(group))
                        .setNegativeButton(android.R.string.cancel, null);
        if (diverged(group)) {
            builder.setPositiveButton(
                    R.string.resolve, (dialog, which) -> resolveGroup(group));
        }
        builder.show();
    }

    /**
     * Pick-a-source resolution of a diverged contact
     * (docs/merged-view.md): choose the replica whose document wins,
     * and its vCard is staged as an ordinary edit onto every other
     * replica, each pushed through its own account's normal path (etag
     * guards included). No merge, no special sync mode.
     */
    private void resolveGroup(Group group) {
        String[] labels = new String[group.replicas.size()];
        for (int index = 0; index < labels.length; index++) {
            Entry entry = group.replicas.get(index);
            labels[index] = entry.book.name + " (" + entry.accountEmail + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.resolve_title)
                .setItems(
                        labels,
                        (dialog, which) ->
                                applyResolution(group, group.replicas.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Stages the source's document onto every other replica of the group. */
    private void applyResolution(Group group, Entry source) {
        java.util.Set<String> staged = new java.util.HashSet<>();
        for (Entry entry : group.replicas) {
            // An m:n replica appears once per book; stage it once, and
            // skip replicas already carrying the source's content.
            if (!staged.add(replicaRefOf(entry))
                    || entry.hash.equals(source.hash)) {
                continue;
            }
            AccountEntry account = accountFor(entry.accountEmail);
            if (account == null) {
                continue;
            }

            base.saveLocal(
                    entry.accountEmail,
                    cardKey(account.account, entry.book.url, entry.card.id),
                    entry.book.url,
                    new Card(
                            entry.card.id,
                            entry.card.uri,
                            entry.card.etag,
                            source.card.vcard));
        }

        toast(getString(R.string.resolve_done));
        renderContacts();
    }

    /**
     * Starts a new contact: in the filtered addressbook when one is
     * open, otherwise asking for the target addressbook (unless only
     * one is subscribed).
     */
    private void addContact() {
        if (currentBook != null) {
            openContact(currentBook.book, currentBook.accountEmail, null);
            return;
        }

        List<BookEntry> books = base.loadSubscribedAddressbooks();
        if (books.isEmpty()) {
            toast(getString(R.string.home_empty));
            return;
        }
        if (books.size() == 1) {
            openContact(books.get(0).book, books.get(0).accountEmail, null);
            return;
        }

        // The default save addressbook (long-press a book on the
        // management screen) skips the prompt while it stays subscribed.
        String defaultUrl = defaultBookUrl();
        for (BookEntry entry : books) {
            if (entry.book.url.equals(defaultUrl)) {
                openContact(entry.book, entry.accountEmail, null);
                return;
            }
        }

        String[] labels = new String[books.size()];
        for (int index = 0; index < labels.length; index++) {
            BookEntry entry = books.get(index);
            labels[index] = entry.book.name + " (" + entry.accountEmail + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_target_title)
                .setItems(
                        labels,
                        (dialog, which) ->
                                openContact(
                                        books.get(which).book,
                                        books.get(which).accountEmail,
                                        null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Shows the sync menu anchored to the sync button. */
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
        content.findViewById(R.id.menu_sync_full)
                .setOnClickListener(
                        view -> {
                            popup.dismiss();
                            syncFull();
                        });
        popup.showAsDropDown(anchor);
    }

    /**
     * Groups the replica pool into merged rows (UID-linked), filters
     * them by the search query and refreshes the list. A group matches
     * the query when any of its replicas does.
     */
    private void renderContacts() {
        TextView sticky = findViewById(R.id.contacts_sticky_letter);
        updateSelectionUi();

        detachedRefs = base.loadDetached();
        links = base.loadLinks();

        Map<String, Group> groups = new java.util.LinkedHashMap<>();
        for (Entry entry : contacts) {
            groups.computeIfAbsent(groupKeyOf(entry), key -> new Group()).replicas.add(entry);
        }

        sortedContacts = new ArrayList<>();
        for (Group group : groups.values()) {
            boolean matches = searchQuery.isEmpty();
            for (Entry entry : group.replicas) {
                if (matches || entry.card.vcard.toLowerCase().contains(searchQuery)) {
                    matches = true;
                    break;
                }
            }
            if (matches) {
                sortedContacts.add(group);
            }
        }
        sortedContacts.sort(
                Comparator.comparing(
                        group -> displayName(group.primary()),
                        String.CASE_INSENSITIVE_ORDER));
        adapter.notifyDataSetChanged();

        boolean empty = sortedContacts.isEmpty();
        findViewById(R.id.contacts_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            // Distinguish a search miss from a genuinely empty addressbook.
            boolean searching = !searchQuery.isEmpty();
            ((android.widget.ImageView) findViewById(R.id.contacts_empty_icon))
                    .setImageResource(
                            searching ? R.drawable.ic_search_x : R.drawable.ic_folder_open);
            ((TextView) findViewById(R.id.contacts_empty_title))
                    .setText(
                            searching ? R.string.empty_search_title : R.string.empty_book_title);
        }
        sticky.setText(empty ? "" : letter(displayName(sortedContacts.get(0).primary())));
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

            Group group = sortedContacts.get(position);
            Entry entry = group.primary();
            String name = displayName(entry);

            TextView avatar = row.findViewById(R.id.contact_avatar);
            avatar.setText(letter(name));
            avatar.setBackground(avatarCircle(name));

            ((TextView) row.findViewById(R.id.contact_name)).setText(name);

            bindLine(row.findViewById(R.id.contact_email), entry.email);
            bindLine(row.findViewById(R.id.contact_phone), entry.phone);
            bindLine(
                    row.findViewById(R.id.contact_accounts),
                    group.replicas.size() > 1
                            ? getString(
                                    diverged(group)
                                            ? R.string.contact_accounts_diverged
                                            : R.string.contact_accounts,
                                    group.replicas.size())
                            : null);

            CheckBox check = row.findViewById(R.id.contact_check);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedKeys.contains(groupKey(group)));

            return row;
        }
    }

    /**
     * The merged identity of one replica: the cluster the user linked
     * it into when one exists, its automatic key otherwise.
     */
    private String groupKeyOf(Entry entry) {
        String natural = naturalKeyOf(entry);
        String cluster = links.get(natural);
        return cluster != null ? cluster : natural;
    }

    /**
     * The automatic group key, before link exceptions: the vCard UID,
     * or the replica itself when it has none or the user detached it.
     */
    private String naturalKeyOf(Entry entry) {
        String ref = replicaRefOf(entry);
        if (detachedRefs.contains(ref)) {
            return "ref\0" + ref;
        }

        if (!entry.uid.isEmpty()) {
            return "uid\0" + entry.uid;
        }
        return "ref\0" + ref;
    }

    /** The replica's link-layer reference. */
    private String replicaRefOf(Entry entry) {
        AccountEntry account = accountFor(entry.accountEmail);
        String key =
                account == null
                        ? CardStore.key(entry.book.url, entry.card.id)
                        : cardKey(account.account, entry.book.url, entry.card.id);
        return CardStore.replicaRef(entry.accountEmail, key);
    }

    private String groupKey(Group group) {
        return groupKeyOf(group.primary());
    }

    /** Links the selected rows into one merged contact. */
    private void linkSelected() {
        List<String> keys = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(groupKey(group))) {
                keys.add(groupKey(group));
            }
        }
        if (keys.size() < 2) {
            return;
        }

        base.linkGroups(keys);
        exitSelection();
        toast(getString(R.string.link_done));
        renderContacts();
    }

    /** Splits a merged row apart: every replica becomes its own contact. */
    private void unlinkGroup(Group group) {
        List<String> members = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        for (Entry entry : group.replicas) {
            members.add(naturalKeyOf(entry));
            refs.add(replicaRefOf(entry));
        }

        base.unlinkGroup(groupKey(group), members, refs);
        toast(getString(R.string.unlink_done));
        renderContacts();
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

    /** The list title gives way to the selected count while selecting. */
    private void updateSelectionUi() {
        TextView title = findViewById(R.id.contacts_title);
        if (selectionMode) {
            title.setText(getString(R.string.selected_count, selectedKeys.size()));
        } else if (currentBook != null) {
            title.setText(currentBook.book.name);
        } else {
            title.setText(R.string.contacts_title);
        }

        // The back arrow only exists on a filtered list: the merged
        // root is the app's root, there is nothing to go back to.
        findViewById(R.id.contacts_back)
                .setVisibility(
                        !selectionMode && currentBook != null ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // Linking needs at least two rows to join.
        findViewById(R.id.contacts_link)
                .setVisibility(
                        selectionMode && selectedKeys.size() > 1 ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_sync).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_books).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
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
        // Deleting a merged row deletes every replica behind it, each
        // staged against its own account.
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(groupKey(group))) {
                for (Entry entry : group.replicas) {
                    AccountEntry account = accountFor(entry.accountEmail);
                    if (account == null) {
                        continue;
                    }
                    base.markDeleted(
                            entry.accountEmail,
                            cardKey(account.account, entry.book.url, entry.card.id),
                            entry.card);
                }
            }
        }
        selectionMode = false;
        selectedKeys.clear();
        reloadContacts();
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

    /** True when the linked replicas' normalized contents differ. */
    private static boolean diverged(Group group) {
        String hash = group.primary().hash;
        for (Entry entry : group.replicas) {
            if (!hash.equals(entry.hash)) {
                return true;
            }
        }
        return false;
    }

    /** The replica's display name from its index, id fallback. */
    private static String displayName(Entry entry) {
        return entry.name.isEmpty() ? entry.card.id : entry.name;
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
    private void openContact(Addressbook book, String accountEmail, Card card) {
        editingBook = book;
        editingAccountEmail = accountEmail;
        editingCard = card;
        editingVcard = card == null ? newVcard() : card.vcard;

        TextView title = findViewById(R.id.contact_title);
        String name = card == null ? "" : cardIndex(card.vcard).optString("name");
        title.setText(
                card == null
                        ? getString(R.string.contact_new)
                        : name.isEmpty() ? card.id : name);
        // A fresh card only exists in its target addressbook; the
        // dialog needs a saved replica to work on.
        findViewById(R.id.contact_books).setVisibility(card == null ? View.GONE : View.VISIBLE);

        form.load(card == null ? null : client.projectCard(card), editingVcard);

        show(PANEL_CONTACT);
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_save).setOnClickListener(view -> saveContact());
        findViewById(R.id.contact_books).setOnClickListener(view -> manageBooks());
        // Back reloads: the addressbooks dialog stages copies and
        // removals the list must reflect.
        findViewById(R.id.contact_back)
                .setOnClickListener(
                        view -> {
                            reloadContacts();
                            show(PANEL_CONTACTS);
                        });
    }

    /**
     * The addressbooks dialog of the open card: checked means the
     * contact exists there, through this replica's memberships or a
     * UID-linked replica elsewhere. Checking adds it: a staged
     * membership on the replica's own account-level backend, a copied
     * replica (same UID, instantly linked in the merged view) anywhere
     * else. Unchecking removes it: a staged membership removal when the
     * replica keeps other books, a staged delete of the replica
     * otherwise. The book the editor was opened from is locked, so the
     * open document never removes itself.
     */
    private void manageBooks() {
        AccountEntry editing = accountFor(editingAccountEmail);
        if (editingCard == null || editing == null) {
            return;
        }

        String uid = cardIndex(editingCard.vcard).optString("uid");
        String key = cardKey(editing.account, editingBook.url, editingCard.id);

        // Where the contact lives: this replica's memberships, plus
        // every UID-linked replica's books.
        java.util.Set<String> memberUrls =
                new java.util.HashSet<>(base.loadMemberships(editingAccountEmail, key));
        Map<String, Entry> replicaByBook = new HashMap<>();
        if (!uid.isEmpty()) {
            for (Entry entry : loadEntries(null)) {
                if (uid.equals(entry.uid)) {
                    memberUrls.add(entry.book.url);
                    replicaByBook.put(entry.book.url, entry);
                }
            }
        }

        List<BookEntry> books = base.loadSubscribedAddressbooks();
        String[] labels = new String[books.size()];
        boolean[] before = new boolean[books.size()];
        int lockedIndex = -1;
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            labels[index] = entry.book.name + " (" + entry.accountEmail + ")";
            before[index] = memberUrls.contains(entry.book.url);
            if (entry.book.url.equals(editingBook.url)) {
                lockedIndex = index;
            }
        }
        int locked = lockedIndex;
        boolean[] after = before.clone();

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_books_title)
                .setMultiChoiceItems(
                        labels,
                        after,
                        (dialog, which, isChecked) -> {
                            if (which == locked && !isChecked) {
                                ((AlertDialog) dialog)
                                        .getListView()
                                        .setItemChecked(which, true);
                                after[which] = true;
                                toast(getString(R.string.manage_books_locked));
                            }
                        })
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> applyBooks(books, before, after, replicaByBook))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Applies the addressbooks dialog: staged memberships, copies, removals. */
    private void applyBooks(
            List<BookEntry> books,
            boolean[] before,
            boolean[] after,
            Map<String, Entry> replicaByBook) {
        AccountEntry editing = accountFor(editingAccountEmail);
        if (editing == null) {
            return;
        }
        String key = cardKey(editing.account, editingBook.url, editingCard.id);

        for (int index = 0; index < books.size(); index++) {
            if (before[index] == after[index]) {
                continue;
            }

            BookEntry target = books.get(index);
            AccountEntry account = accountFor(target.accountEmail);
            if (account == null) {
                continue;
            }
            boolean ownAccount = target.accountEmail.equals(editingAccountEmail);
            boolean accountLevel = CardamumClient.isAccountLevel(account.account);

            if (after[index]) {
                if (ownAccount && accountLevel) {
                    // Same account, m:n backend: a membership change.
                    base.stageMembership(editingAccountEmail, key, target.book.url, true);
                } else {
                    // Anywhere else: a copied replica, same UID, so the
                    // merged view links it instantly.
                    String uid = cardIndex(editingCard.vcard).optString("uid");
                    String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                    String copyKey = cardKey(account.account, target.book.url, id);

                    // Google creates land in myContacts; the group
                    // membership pushes right after the create (the
                    // key rename of confirmPush carries it over).
                    if (account.account.baseUrl.startsWith(CardamumClient.GOOGLE_PREFIX)
                            && !"myContacts".equals(target.book.id)) {
                        base.stageMembership(
                                target.accountEmail, copyKey, target.book.url, true);
                    }
                    base.saveLocal(
                            target.accountEmail,
                            copyKey,
                            target.book.url,
                            new Card(id, null, null, editingCard.vcard));
                }
            } else if (ownAccount
                    && accountLevel
                    && !target.book.url.equals(editingBook.url)) {
                base.stageMembership(editingAccountEmail, key, target.book.url, false);
            } else {
                Entry replica = replicaByBook.get(target.book.url);
                if (replica == null) {
                    continue;
                }
                AccountEntry owner = accountFor(replica.accountEmail);
                if (owner == null) {
                    continue;
                }
                String replicaKey =
                        cardKey(owner.account, replica.book.url, replica.card.id);

                if (CardamumClient.isAccountLevel(owner.account)
                        && base.loadMemberships(replica.accountEmail, replicaKey).size() > 1) {
                    // The replica stays, only this membership goes.
                    base.stageMembership(
                            replica.accountEmail, replicaKey, target.book.url, false);
                } else {
                    // Last place of this replica: the replica goes.
                    base.markDeleted(replica.accountEmail, replicaKey, replica.card);
                }
            }
        }

        toast(getString(R.string.manage_books_done));
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

        AccountEntry account = accountFor(editingAccountEmail);
        if (account == null) {
            // The account was deleted while the editor was open.
            toast(getString(R.string.save_failed));
            return;
        }

        try {
            String vcard = client.applyCard(editingVcard, form.collect());
            String uid = cardIndex(vcard).optString("uid");
            String id =
                    editingCard == null
                            ? (uid.isEmpty() ? UUID.randomUUID().toString() : uid)
                            : editingCard.id;
            String uri = editingCard == null ? null : editingCard.uri;
            String etag = editingCard == null ? null : editingCard.etag;
            base.saveLocal(
                    editingAccountEmail,
                    cardKey(account.account, editingBook.url, id),
                    editingBook.url,
                    new Card(id, uri, etag, vcard));
        } catch (Exception error) {
            showError(error, R.string.save_failed);
            return;
        }

        reloadContacts();
        show(PANEL_CONTACTS);
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    private void reloadContacts() {
        contacts = loadEntries(currentBook);
        renderContacts();
    }

    // ---- vCard helpers ------------------------------------------------------

    private static String newVcard() {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + UUID.randomUUID() + "\r\nEND:VCARD\r\n";
    }

    /**
     * The bridge's index of a vCard for one-off lookups (name, uid);
     * the hot paths read the same index from the store's columns
     * instead. Empty on a parse failure.
     */
    private JSONObject cardIndex(String vcard) {
        try {
            return client.indexCard(vcard);
        } catch (Exception error) {
            Log.w("cardamum", "card index failed", error);
            return new JSONObject();
        }
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
