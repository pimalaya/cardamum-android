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
    private static final int PANEL_CONTACTS = 4;
    private static final int PANEL_CONTACT = 5;
    private static final int PANEL_SETTINGS = 6;
    private static final int PANEL_HOME = 7;

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

    /** The addressbook open in the contacts screen. */
    private BookEntry currentBook;

    /** The open addressbook's cards. */
    private List<Entry> contacts = new ArrayList<>();

    /** The contacts list, sorted; backs the recycling adapter. */
    private List<Entry> sortedContacts = new ArrayList<>();
    private ContactsAdapter adapter;

    /** Lower-cased raw-vCard filter from the search bar; empty shows all. */
    private String searchQuery = "";

    /** Debounced search refresh, so typing does not re-render per keystroke. */
    private Runnable pendingSearch;

    /** Sync to re-run once the contacts permission is granted, if any. */
    private Runnable afterContactsPermission;

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
                    show(PANEL_HOME);
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
                                this::startGoogleOauth));
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
                                getString(R.string.config_soon),
                                null));
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
     * Starts the Google CardDAV OAuth grant: prepares the PKCE session,
     * then opens the authorization URL in the browser. The redirect
     * comes back through {@link #onNewIntent} on the reversed-client-id
     * custom scheme.
     */
    private void startGoogleOauth() {
        pendingOauth =
                new OauthSession(
                        Oauth.GOOGLE_CLIENT_ID, null, Oauth.GOOGLE_REDIRECT_URI, Oauth.GOOGLE_SCOPE);
        pendingTokenEndpoint = Oauth.GOOGLE_TOKEN_ENDPOINT;
        pendingBaseUrl = Oauth.googleCardDavBase(pendingEmail);
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

    // ---- Home screen ----------------------------------------------------------

    private void setUpHomePanel() {
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
            name.setText(entry.book.name);
            name.setTextSize(16);
            name.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            name.setLayoutParams(
                    new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(16), dp(16), dp(16));
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            row.addView(name);

            CheckBox sync = new CheckBox(this);
            sync.setChecked(entry.subscribed);
            sync.setOnClickListener(view -> confirmToggleSync(entry, sync));
            row.addView(sync);

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
        // 20dp end padding centres the 24dp chevron on the app bars'
        // icon column (glyph centre 32dp from the screen edge).
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        header.setBackgroundColor(getColor(R.color.surface));
        header.setForeground(getDrawable(resolveAttr(android.R.attr.selectableItemBackground)));
        header.addView(label);
        header.addView(chevron);

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

        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            AccountEntry account = accountFor(entry.accountEmail);
            if (account == null) {
                continue;
            }

            try {
                syncBook(account.account, entry, outcome);
            } catch (Exception error) {
                // An expired OAuth access token: refresh it and retry
                // the addressbook once.
                if (expiredToken(error) && account.refreshToken != null) {
                    try {
                        syncBook(refreshAccount(account), entry, outcome);
                        continue;
                    } catch (Exception retryError) {
                        error = retryError;
                    }
                }

                // One addressbook failing (revoked account, server down)
                // must not block the others.
                Log.w("cardamum", "sync failed for " + entry.book.name, error);
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
     * One addressbook's fetch-push-refetch cycle, accumulating what it
     * pulled, pushed and merged into `outcome`.
     */
    private void syncBook(Account acc, BookEntry entry, SyncOutcome outcome) {
        Addressbook book = entry.book;

        // The store as of the last sync, to count what the fetch brings.
        Map<String, String> before = new HashMap<>();
        for (Card card : base.loadCards(book.url)) {
            before.put(card.id, card.etag);
        }

        // Fetch first, so staged rows learn the server's resource names
        // before the push addresses them.
        List<Card> fetched = client.listCards(acc, book.url);

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

        base.replaceCards(book.url, fetched);

        Map<String, String> serverEtags = new HashMap<>();
        for (Card card : fetched) {
            serverEtags.put(card.id, card.etag);
        }

        List<CardStore.Pending> pending = base.loadPending(book.url);
        if (!pending.isEmpty()) {
            push(acc, book, pending, serverEtags, outcome);

            // The push changed the remote; re-fetch its resulting state.
            base.replaceCards(book.url, client.listCards(acc, book.url));
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
     * Pushes the addressbook's staged local changes to the remote,
     * accumulating the pushed and merged counts into `outcome`. A 412
     * means the remote changed under the staged edit: the change stays
     * staged (merged) and retries next sync (NOTE: keep-both resolution
     * lands with the io-offline engine). Any other failure aborts the
     * sync (offline fallback).
     */
    private void push(Account account, Addressbook book, List<CardStore.Pending> changes,
            Map<String, String> serverEtags, SyncOutcome outcome) {
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
                            client.deleteCard(account, book.url, unguarded);
                            base.removeCard(book.url, pending.card.id);
                        } else {
                            base.confirmPush(
                                    book.url,
                                    pending.card.id,
                                    client.updateCard(
                                            account, book.url, unguarded, pending.baseVcard));
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

    /** Sorts the open addressbook's cards (search-filtered) and refreshes the list. */
    private void renderContacts() {
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
