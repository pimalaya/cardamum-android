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
 * stay strictly per-replica). A merged row opens one edit form for the
 * whole contact and saving fans the form out onto every replica.
 * Addressbooks live behind a management screen; syncs are manual, from
 * the Sync menu.
 */
public class MainActivity extends Activity {
    // NOTE: contacts, contact and advanced are the content flipper's
    // child indexes; home and auth are whole-frame overlays sliding
    // over it, bar included (only the FAB stays above them).
    private static final int PANEL_CONTACTS = 0;
    private static final int PANEL_CONTACT = 1;
    private static final int PANEL_ADVANCED = 2;
    private static final int PANEL_HOME = 3;
    private static final int PANEL_AUTH = 4;

    /** The auth flow's steps, inside its own flipper under one bar. */
    private static final int STEP_EMAIL = 0;
    private static final int STEP_CONFIG = 1;
    private static final int STEP_BOOKS = 2;

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
        final String info;
        final String uid;
        final String hash;

        Entry(Addressbook book, String accountEmail, CardStore.Indexed indexed) {
            this.book = book;
            this.accountEmail = accountEmail;
            this.card = indexed.card;
            this.name = indexed.name;
            this.email = indexed.email;
            this.phone = indexed.phone;
            this.info = indexed.info;
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
    private ViewFlipper authFlipper;
    private ContactForm form;

    /** Every connected account (multi-account). */
    private final List<AccountEntry> accounts = new ArrayList<>();

    /** Onboarding state: the entered email and its searched service configs. */
    private String pendingEmail;
    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

    /** Action of the config option currently picked (null until one is). */
    private Runnable selectedConfig;

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

    /** The replica pool of the contacts screen. */
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

    /** The just-connected account, persisted when the selection confirms. */
    private AccountEntry pendingAccount;

    /** The just-connected account's fetched addressbooks. */
    private List<Addressbook> pendingBooks;

    /** The merged group's replicas behind the editor; saving fans out. */
    private List<Entry> editingReplicas = new ArrayList<>();

    /** The replica the Merge action keeps; every other card is removed. */
    private Entry mergeSurvivor;

    /** The addressbooks dialog's desired state, applied on save. */
    private Map<String, Boolean> pendingBookState;

    /** Whether the contacts screen's in-bar search field is open. */
    private boolean searchOpen;

    /** The editor's bar title (the shared bar is retitled per screen). */
    private CharSequence editingTitle = "";

    /** Whether the open contact offers the advanced raw editor. */
    private boolean advancedAvailable;

    /** The screen currently shown: a flipper panel, or an overlay. */
    private int screen = PANEL_CONTACTS;

    /** The advanced editor's working document; null until it opens. */
    private String advancedVcard;

    /** Set by a raw edit: staging must not skip on an unchanged hash. */
    private boolean advancedDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new SecureStore(this);
        base = new CardStore(this);
        flipper = findViewById(R.id.flipper);
        authFlipper = findViewById(R.id.auth_flipper);
        form = new ContactForm(this);

        setUpEmailPanel();
        setUpBooksPanel();
        setUpContactsPanel();
        setUpContactPanel();
        setUpHomePanel();

        for (int id :
                new int[] {
                    R.id.email_submit, R.id.config_continue, R.id.books_continue, R.id.fab,
                }) {
            setUpFab(id);
        }
        findViewById(R.id.fab).setOnClickListener(view -> onFabClick());
        findViewById(R.id.bar_back).setOnClickListener(view -> onBarBack());

        accounts.addAll(store.loadAll());
        if (accounts.isEmpty()) {
            // First run: the onboarding opens over the (empty)
            // addressbooks drawer, so backing out of it lands there.
            startAuth();
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
     * Enters the connection flow. The sheet always rises over the
     * addressbooks drawer (opened first when not already there), so
     * backing out of the flow always lands on the listing, empty or
     * not; only finishing the flow lands on the contacts root.
     */
    private void startAuth() {
        if (screen != PANEL_HOME) {
            openBooksManager();
        }
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        ((EditText) findViewById(R.id.email_input)).setText("");

        showAuth(STEP_EMAIL);
    }

    /** Navigates the auth flow forward: the step slides in from the right. */
    private void showAuth(int step) {
        showAuth(step, false);
    }

    /** Navigates the auth flow back: the step slides in from the left. */
    private void showAuthBack(int step) {
        showAuth(step, true);
    }

    /**
     * Shows an auth step under the persistent bar: only the inner step
     * view transitions. Entering the flow from outside jumps the inner
     * flipper without animation (the outer panel slide is the
     * transition).
     */
    private void showAuth(int step, boolean back) {
        boolean inside = screen == PANEL_AUTH;
        if (inside) {
            hideKeyboard();
            authFlipper.setInAnimation(this, back ? R.anim.slide_in_left : R.anim.slide_in_right);
            authFlipper.setOutAnimation(
                    this, back ? R.anim.slide_out_right : R.anim.slide_out_left);
        } else {
            authFlipper.setInAnimation(null);
            authFlipper.setOutAnimation(null);
        }

        authFlipper.setDisplayedChild(step);
        if (inside) {
            applyChrome(PANEL_AUTH);
        } else {
            // The flow rises over the current screen like a sheet.
            openOverlay(PANEL_AUTH);
        }
    }

    /**
     * The auth bar per step: the step's title, no back arrow on the
     * welcome step when no account is stored (nothing to go back to),
     * and the cross only when one is (the flow is escape-free
     * otherwise).
     */
    private void applyAuthChrome() {
        int step = authFlipper.getDisplayedChild();
        ((TextView) findViewById(R.id.auth_title))
                .setText(
                        step == STEP_EMAIL
                                ? R.string.auth_step_email
                                : step == STEP_CONFIG
                                        ? R.string.auth_step_config
                                        : R.string.auth_step_books);
    }

    /** The auth bar's back arrow, per step. */
    private void authBack() {
        int step = authFlipper.getDisplayedChild();
        if (step == STEP_BOOKS) {
            // Nothing persists before the selection confirms, so the
            // books step steps back into the flow like any other.
            showAuthBack(STEP_CONFIG);
        } else if (step == STEP_CONFIG) {
            showAuthBack(STEP_EMAIL);
        } else {
            // The email step is only reachable when adding an account.
            cancelAuth();
        }
    }

    /**
     * Leaves the auth flow without finishing: the sheet slides down
     * onto the addressbooks drawer it always rises over. Only
     * finishing the whole flow lands on the contacts root.
     */
    private void cancelAuth() {
        closeOverlay(PANEL_AUTH);
        screen = PANEL_HOME;
        applyChrome(PANEL_HOME);
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
        if (screen == PANEL_CONTACTS && selectionMode) {
            exitSelection();
            return;
        }

        switch (screen) {
            case PANEL_AUTH:
                authBack();
                break;
            case PANEL_CONTACTS:
                if (searchOpen) {
                    closeSearch();
                    break;
                }
                // The merged list is the app's root itself.
                super.onBackPressed();
                break;
            case PANEL_CONTACT:
                closeContact();
                break;
            case PANEL_ADVANCED:
                closeAdvanced();
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
        searchQuery = "";
        closeSearch();
        exitSelection();

        contacts = loadEntries();
        renderContacts();

        // Landing on the root is always a return, but the overlays
        // leave their own way: the addressbooks drawer back out to the
        // left, the auth sheet back down, the root staying put.
        if (screen == PANEL_AUTH) {
            // A drawer beneath the sheet just goes: the sheet's slide
            // down reveals the root directly.
            findViewById(R.id.overlay_home).setVisibility(View.GONE);
            closeOverlay(PANEL_AUTH);
            screen = PANEL_CONTACTS;
            applyChrome(PANEL_CONTACTS);
        } else if (screen == PANEL_HOME) {
            closeOverlay(PANEL_HOME);
            screen = PANEL_CONTACTS;
            applyChrome(PANEL_CONTACTS);
        } else {
            showBack(PANEL_CONTACTS);
        }
    }

    /**
     * Refreshes the addressbooks management screen and slides it over
     * the contacts list like a drawer (the list stays put).
     */
    private void openBooksManager() {
        reloadHome();
        openOverlay(PANEL_HOME);
    }

    // ---- Connection screen --------------------------------------------------

    private void setUpEmailPanel() {
        EditText email = findViewById(R.id.email_input);
        findViewById(R.id.auth_back).setOnClickListener(view -> authBack());
        findViewById(R.id.auth_cancel).setOnClickListener(view -> cancelAuth());
        findViewById(R.id.config_continue)
                .setOnClickListener(
                        view -> {
                            if (selectedConfig != null) {
                                selectedConfig.run();
                            }
                        });

        View submit = findViewById(R.id.email_submit);
        // Continue stays disabled until the field holds something
        // plausible (an email, a server, or a connection URI), so an
        // empty or malformed input is simply not submittable.
        setFabEnabled(R.id.email_submit, false);
        email.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        String address = s.toString().trim();
                        setFabEnabled(
                                R.id.email_submit,
                                !address.isEmpty() && !address.contains(" "));
                    }
                });

        submit.setOnClickListener(
                view -> {
                    // The panel stays visible while the search runs;
                    // drop the field's focus so the keyboard leaves.
                    hideKeyboard();

                    // One field covers every case, the CLI way: an
                    // email goes through discovery, anything else (a
                    // host[:port] or a connection URI) is a server to
                    // configure by hand.
                    String address = email.getText().toString().trim();
                    pendingEmail = address;
                    if (address.contains("://") || !address.contains("@")) {
                        showManualConfigs(address);
                    } else if (Provider.detect(address) == Provider.OTHER) {
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
     * Toggles an auth continue FAB between its arrow and an on-disc
     * loader, the button disabled while loading.
     */
    private void setAuthLoading(int buttonId, int progressId, boolean loading) {
        android.widget.ImageButton button = findViewById(buttonId);
        setFabEnabled(buttonId, !loading);
        button.setImageAlpha(loading ? 0 : 255);
        findViewById(progressId).setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /** Enables or disables an auth continue FAB, dimming its disc. */
    private void setFabEnabled(int id, boolean enabled) {
        View fab = findViewById(id);
        fab.setEnabled(enabled);
        fab.setAlpha(enabled ? 1f : 0.4f);
    }

    /**
     * Styles a floating action button: its icon tinted to contrast the
     * accent disc's luminance.
     */
    private void setUpFab(int id) {
        android.widget.ImageButton fab = findViewById(id);
        int accent = resolveColor(android.R.attr.colorAccent);
        int tint =
                android.graphics.Color.luminance(accent) > 0.5f
                        ? android.graphics.Color.BLACK
                        : android.graphics.Color.WHITE;
        fab.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
    }

    /** The config Continue back to idle: enabled once an option is picked. */
    private void resetConfigContinue() {
        setAuthLoading(R.id.config_continue, R.id.config_progress, false);
        setFabEnabled(R.id.config_continue, selectedConfig != null);
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

        showAuth(STEP_CONFIG);
    }

    /**
     * The config options for a server typed directly in the first
     * field (a host[:port], or a connection URI): no discovery, the
     * same server offered over CardDAV and JMAP, the credentials asked
     * next (an empty login sends the secret as a Bearer token).
     */
    private void showManualConfigs(String address) {
        String url = address.contains("://") ? address : "https://" + address;
        String host = hostOf(url);
        String jmapUrl = CardamumClient.JMAP_PREFIX + url.replaceFirst("^https?://", "");

        ((TextView) findViewById(R.id.config_email)).setText(pendingEmail);
        selectedConfig = null;
        resetConfigContinue();

        LinearLayout container = findViewById(R.id.config_container);
        container.removeAllViews();
        container.addView(
                configItem(
                        getString(R.string.config_carddav),
                        host,
                        getString(R.string.config_password),
                        () -> promptCredentials(url, host)));
        container.addView(
                configItem(
                        getString(R.string.config_jmap),
                        host,
                        getString(R.string.config_password),
                        () -> promptCredentials(jmapUrl, host)));

        showAuth(STEP_CONFIG);
    }

    /**
     * Prompts for the login and secret of a manually-entered server
     * (nothing discovered to prefill the login; leaving it empty sends
     * the secret as a Bearer token), then verifies the account.
     */
    private void promptCredentials(String baseUrl, String host) {
        EditText login = customOauthField(R.string.custom_login, "");
        EditText secret = new EditText(this);
        secret.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint(R.string.password_hint);

        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        fields.addView(login);
        fields.addView(secret);

        new AlertDialog.Builder(this)
                .setTitle(R.string.password_title)
                .setMessage(getString(R.string.password_server, pendingEmail, host))
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String password = secret.getText().toString();
                            if (password.isEmpty()) {
                                toast(getString(R.string.password_empty));
                                return;
                            }
                            connect(
                                    new Account(
                                            baseUrl,
                                            login.getText().toString().trim(),
                                            password),
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
                        setFabEnabled(R.id.config_continue, true);
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
     * Verifies the account connects, then moves to the addressbook
     * selection. Nothing persists yet: the account and its books only
     * store when the selection confirms, so backing out of the flow
     * before that leaves everything untouched. The last four parameters
     * carry the refresh material of an OAuth account (all null for a
     * password one), so expired access tokens can be refreshed on later
     * syncs.
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
                                    pendingAccount =
                                            new AccountEntry(
                                                    candidate,
                                                    email,
                                                    refreshToken,
                                                    tokenEndpoint,
                                                    clientId,
                                                    clientSecret);
                                    pendingBooks = fetched;
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

        setAuthLoading(R.id.books_continue, R.id.books_progress, false);
        updateBooksContinue();
        showAuth(STEP_BOOKS);
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
        setFabEnabled(R.id.books_continue, any);
    }

    /**
     * The flow's real commit: persists the connected account and its
     * addressbooks, applies the checked subscriptions, then runs the
     * account's first sync.
     */
    private void confirmBooks() {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (CheckBox box : bookBoxes) {
            if (box.isChecked()) {
                subscribed.add((String) box.getTag());
            }
        }

        accounts.removeIf(entry -> entry.email.equals(connectedEmail));
        accounts.add(pendingAccount);
        store.add(pendingAccount);
        pendingAccount = null;
        base.replaceAddressbooks(connectedEmail, pendingBooks);
        base.setSubscriptions(connectedEmail, subscribed);

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
        if (flipper != null
                && screen == PANEL_AUTH
                && authFlipper.getDisplayedChild() == STEP_CONFIG) {
            resetConfigContinue();
        }
    }

    // ---- Addressbooks management screen -----------------------------------------

    private void setUpHomePanel() {
        // The arrow and the cross both close the drawer.
        findViewById(R.id.home_back).setOnClickListener(view -> goHome());
        findViewById(R.id.home_close).setOnClickListener(view -> goHome());
    }

    /**
     * Rebuilds the addressbooks listing: every addressbook grouped by
     * account with its subscription checkbox; tapping anywhere on a row
     * toggles it, instantly and without confirmation (the store and the
     * phone catch up on the next sync passes). Long-pressing a header
     * offers to delete the account.
     */
    private void reloadHome() {
        homeBooks = base.loadAllAddressbooks();

        LinearLayout container = findViewById(R.id.home_container);
        container.removeAllViews();

        findViewById(R.id.home_empty)
                .setVisibility(homeBooks.isEmpty() ? View.VISIBLE : View.GONE);
        if (homeBooks.isEmpty()) {
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
            // The trailing checkbox rides in a 48dp slot with a 4dp end
            // padding, so its centre lines up with the app-bar icon
            // column and the account-header chevron.
            row.setPadding(dp(16), 0, dp(4), 0);
            row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
            row.addView(name);

            CheckBox sync = new CheckBox(this);
            sync.setChecked(entry.subscribed);
            sync.setOnClickListener(view -> toggleSync(entry));
            row.addView(iconSlot(sync));

            // The whole row is the checkbox's tap target: subscribing
            // is the only action a book row carries.
            row.setOnClickListener(view -> toggleSync(entry));
            books.addView(row);
        }
    }

    /**
     * Flips one addressbook's subscription within its account. A pure
     * view filter: the cached cards stay and reappear on re-check;
     * syncs and the phone projection only cover subscribed books.
     */
    private void toggleSync(BookEntry entry) {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (BookEntry candidate : base.loadAllAddressbooks()) {
            if (!candidate.accountEmail.equals(entry.accountEmail)) {
                continue;
            }
            boolean keep =
                    candidate.book.url.equals(entry.book.url)
                            ? !entry.subscribed
                            : candidate.subscribed;
            if (keep) {
                subscribed.add(candidate.book.url);
            }
        }
        base.setSubscriptions(entry.accountEmail, subscribed);
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

    /**
     * The replica pool from the store: every subscribed addressbook's
     * cards, tagged with their book and account.
     */
    private List<Entry> loadEntries() {
        List<Entry> entries = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
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

        // Self-heal: an account whose addressbooks are missing from
        // the base (a schema rebuild dropped them) gets them
        // re-fetched, all-subscribed, so the sync has something to
        // walk again.
        java.util.Set<String> known = new java.util.HashSet<>();
        for (BookEntry entry : base.loadAllAddressbooks()) {
            known.add(entry.accountEmail);
        }
        for (AccountEntry account : new ArrayList<>(accounts)) {
            if (known.contains(account.email)) {
                continue;
            }
            try {
                base.replaceAddressbooks(
                        account.email, client.listAddressbooks(account.account));
            } catch (Exception error) {
                Log.w("cardamum", "addressbook recovery failed", error);
            }
        }

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
                push(acc, email, pending, cardsById(rowCards(rows)), outcome);
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
            push(acc, email, pending, cardsById(fetched), outcome);

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

    /** The fetched cards keyed by id. */
    private static Map<String, Card> cardsById(List<Card> cards) {
        Map<String, Card> byId = new HashMap<>();
        for (Card card : cards) {
            byId.put(card.id, card);
        }
        return byId;
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
     * edit: both sides three-way merge against the staged base (the
     * local side wins same-field collisions, an update beats a
     * removal) and the merged card pushes against the fresh server
     * state, counted as merged. Any other failure aborts the sync
     * (offline fallback).
     */
    private void push(Account account, String email, List<CardStore.Pending> changes,
            Map<String, Card> serverCards, SyncOutcome outcome) {
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
                    Card server = serverCards.get(pending.card.id);
                    String serverEtag = server == null ? null : server.etag;

                    if (serverEtag != null && serverEtag.equals(pending.card.etag)) {
                        // Server quirk (posteo's SabreDAV): some
                        // resources carry no internal ETag, so their
                        // If-Match always fails even though the listing
                        // serves one. The listing was fetched moments
                        // ago; when it still matches the staged base,
                        // the remote is provably unchanged and the
                        // write is safe without If-Match (the same
                        // guarantee, enforced by us instead).
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
                    } else if (server != null && pending.deleted) {
                        // The remote updated what the delete targeted:
                        // the update wins over the removal, the staged
                        // delete is dropped and the server card adopted.
                        Log.w(
                                "cardamum",
                                "delete conflict on " + pending.card.id + ": remote update wins");
                        base.confirmPush(
                                email,
                                pending.key,
                                cardKey(account, bookUrl, server.id),
                                server);
                        outcome.merged++;
                    } else if (server != null && pending.baseVcard != null) {
                        // The remote genuinely changed under the staged
                        // edit: three-way merge both sides against the
                        // staged base and push the merged card against
                        // the fresh server state.
                        Log.w(
                                "cardamum",
                                "push conflict on " + pending.card.id + ": three-way merging");
                        String merged =
                                client.mergeCardChanges(
                                        pending.baseVcard, pending.card.vcard, server.vcard);
                        Card resolved =
                                new Card(pending.card.id, server.uri, server.etag, merged);
                        Card updated;
                        try {
                            updated = client.updateCard(account, bookUrl, resolved, server.vcard);
                        } catch (Exception retry) {
                            String retryMessage = retry.getMessage();
                            if (retryMessage == null || !retryMessage.contains("HTTP 412")) {
                                throw retry;
                            }
                            // The no-internal-ETag quirk again: the
                            // merge just reconciled against the state
                            // fetched moments ago, so the unguarded
                            // write carries the same guarantee.
                            updated =
                                    client.updateCard(
                                            account,
                                            bookUrl,
                                            new Card(
                                                    pending.card.id,
                                                    server.uri,
                                                    null,
                                                    merged),
                                            server.vcard);
                        }
                        base.confirmPush(
                                email,
                                pending.key,
                                cardKey(account, bookUrl, updated.id),
                                updated);
                        outcome.merged++;
                    } else {
                        // No server state to reconcile against (the
                        // resource vanished, or no base was captured):
                        // the change stays staged for the next sync.
                        outcome.merged++;
                        Log.w(
                                "cardamum",
                                "push conflict on "
                                        + pending.card.id
                                        + ": staged etag "
                                        + pending.card.etag
                                        + ", server etag "
                                        + serverEtag);
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
        findViewById(R.id.contacts_menu).setOnClickListener(view -> openBooksManager());
        findViewById(R.id.contacts_merge).setOnClickListener(view -> mergeSelected());
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
     * Opens a merged row in the editor: one form for the whole contact
     * (docs/merged-view.md), the union of the replicas' documents with
     * per-field alternative chips when they diverge. Saving fans the
     * form out onto every replica.
     */
    private void openGroup(Group group) {
        openMerged(group.replicas);
    }

    /**
     * Starts a new contact, asking for the target addressbook whenever
     * more than one is subscribed: an explicit choice beats a default
     * landing cards where the user does not expect them.
     */
    private void addContact() {
        // No account yet: the plus leads straight into onboarding.
        if (accounts.isEmpty()) {
            startAuth();
            return;
        }

        List<BookEntry> books = base.loadSubscribedAddressbooks();
        if (books.isEmpty()) {
            toast(getString(R.string.home_empty));
            return;
        }
        if (books.size() == 1) {
            openNewContact(books.get(0).book, books.get(0).accountEmail);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_target_title)
                .setItems(
                        bookLabels(books),
                        (dialog, which) ->
                                openNewContact(
                                        books.get(which).book,
                                        books.get(which).accountEmail))
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
        content.findViewById(R.id.menu_find_duplicates)
                .setOnClickListener(
                        view -> {
                            popup.dismiss();
                            findDuplicates();
                        });
        popup.showAsDropDown(anchor);
    }

    // ---- Duplicate remover ---------------------------------------------------

    /**
     * Scans every replica for likely duplicates (exact normalized
     * email, phone or full-name matches, computed by the bridge) and
     * reviews the groups one by one. Dismissed groups are remembered
     * and never proposed again.
     */
    private void findDuplicates() {
        Map<String, Entry> byRef = new HashMap<>();
        org.json.JSONArray cards = new org.json.JSONArray();
        try {
            for (Entry entry : contacts) {
                String ref = replicaRefOf(entry);
                if (byRef.putIfAbsent(ref, entry) == null) {
                    cards.put(new JSONObject().put("ref", ref).put("vcard", entry.card.vcard));
                }
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }

        io.execute(
                () -> {
                    List<List<Entry>> groups = new ArrayList<>();
                    Exception failure = null;
                    try {
                        org.json.JSONArray found = client.findDuplicates(cards);
                        for (int index = 0; found != null && index < found.length(); index++) {
                            org.json.JSONArray refs =
                                    found.optJSONObject(index).optJSONArray("refs");
                            List<Entry> members = new ArrayList<>();
                            for (int at = 0; refs != null && at < refs.length(); at++) {
                                Entry entry = byRef.get(refs.optString(at));
                                if (entry != null) {
                                    members.add(entry);
                                }
                            }
                            if (members.size() >= 2
                                    && !base.isDuplicateDismissed(duplicateKey(members))) {
                                groups.add(members);
                            }
                        }
                    } catch (Exception error) {
                        Log.w("cardamum", "duplicate scan failed", error);
                        failure = error;
                    }

                    List<List<Entry>> pending = groups;
                    Exception scanFailure = failure;
                    main.post(
                            () -> {
                                if (scanFailure != null) {
                                    showError(scanFailure, R.string.dup_none);
                                    return;
                                }
                                if (pending.isEmpty()) {
                                    toast(getString(R.string.dup_none));
                                } else {
                                    reviewDuplicate(pending, 0);
                                }
                            });
                });
    }

    /**
     * Reviews one duplicate group: Ignore remembers the dismissal and
     * walks on, Merge runs the normal merge flow (and ends the review,
     * the form needs the screen), Link makes the cards one contact by
     * sharing a UID, offered only when every card lives in its own
     * addressbook (servers enforce UID uniqueness per collection).
     */
    private void reviewDuplicate(List<List<Entry>> groups, int index) {
        if (index >= groups.size()) {
            toast(getString(R.string.dup_done));
            return;
        }

        List<Entry> members = groups.get(index);
        StringBuilder lines = new StringBuilder();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Entry entry : members) {
            if (!seen.add(replicaRefOf(entry))) {
                continue;
            }
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append(displayName(entry))
                    .append(" · ")
                    .append(bookLabel(entry.book.name, entry.accountEmail));
        }

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dup_title, index + 1, groups.size()))
                        .setMessage(lines.toString())
                        .setPositiveButton(
                                R.string.dup_merge,
                                (dialog, which) -> mergeReplicas(members))
                        .setNegativeButton(
                                R.string.dup_ignore,
                                (dialog, which) -> {
                                    base.dismissDuplicate(duplicateKey(members));
                                    reviewDuplicate(groups, index + 1);
                                });
        if (linkable(members)) {
            builder.setNeutralButton(
                    R.string.dup_link,
                    (dialog, which) -> {
                        linkDuplicates(members);
                        reviewDuplicate(groups, index + 1);
                    });
        }
        builder.show();
    }

    /** The group's dismissal key: its sorted replica refs. */
    private String duplicateKey(List<Entry> members) {
        List<String> refs = new ArrayList<>();
        for (Entry entry : members) {
            if (!refs.contains(replicaRefOf(entry))) {
                refs.add(replicaRefOf(entry));
            }
        }
        java.util.Collections.sort(refs);
        return String.join("", refs);
    }

    /**
     * Whether the group can Link: at least two cards, each in its own
     * addressbook (a same-book pair sharing a UID would collide).
     */
    private boolean linkable(List<Entry> members) {
        Map<String, String> refByBook = new HashMap<>();
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (Entry entry : members) {
            String ref = replicaRefOf(entry);
            refs.add(ref);
            String other = refByBook.putIfAbsent(entry.book.url, ref);
            if (other != null && !other.equals(ref)) {
                return false;
            }
        }
        return refs.size() >= 2;
    }

    /**
     * Links the cards into one contact by staging the same UID on all
     * of them; transparent UID grouping does the rest.
     */
    private void linkDuplicates(List<Entry> members) {
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
            java.util.Set<String> staged = new java.util.HashSet<>();
            for (Entry entry : members) {
                if (!staged.add(replicaRefOf(entry)) || uid.equals(entry.uid)) {
                    continue;
                }
                AccountEntry owner = accountFor(entry.accountEmail);
                if (owner == null) {
                    continue;
                }
                String vcard = client.setCardUid(entry.card.vcard, uid);
                base.saveLocal(
                        entry.accountEmail,
                        cardKey(owner.account, entry.book.url, entry.card.id),
                        entry.book.url,
                        new Card(entry.card.id, entry.card.uri, entry.card.etag, vcard));
            }
        } catch (Exception error) {
            showError(error, R.string.save_failed);
        }
        reloadContacts();
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

        // One empty state for every cause: a search miss, an empty
        // addressbook, or no account at all.
        boolean empty = sortedContacts.isEmpty();
        findViewById(R.id.contacts_empty).setVisibility(empty ? View.VISIBLE : View.GONE);
        sticky.setText(empty ? "" : letter(displayName(sortedContacts.get(0).primary())));

        // Rows are uniform (empty sub-lines keep their space): size the
        // sticky letter box to the row height once, so both letters'
        // centres align.
        ListView list = findViewById(R.id.contacts_list);
        list.post(
                () -> {
                    View first = list.getChildAt(0);
                    if (first != null && sticky.getLayoutParams().height != first.getHeight()) {
                        sticky.getLayoutParams().height = first.getHeight();
                        sticky.requestLayout();
                    }
                });
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

            // One supporting line: the first phone, else the first
            // email, else the card's fallback info.
            String subtitle = entry.phone;
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.email;
            }
            if (subtitle == null || subtitle.isEmpty()) {
                subtitle = entry.info;
            }
            bindLine(row.findViewById(R.id.contact_subtitle), subtitle);

            // The link glyph and card count, for a contact backed by
            // several physical cards.
            int cards = distinctRefs(group.replicas).size();
            row.findViewById(R.id.contact_link_icon)
                    .setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            TextView links = row.findViewById(R.id.contact_links);
            links.setVisibility(cards > 1 ? View.VISIBLE : View.GONE);
            links.setText(String.valueOf(cards));

            // The trailing slot: the selection checkbox, or the
            // divergence flag outside selection.
            boolean isDiverged = diverged(group);
            CheckBox check = row.findViewById(R.id.contact_check);
            check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            check.setChecked(selectedKeys.contains(groupKey(group)));
            android.widget.ImageView danger = row.findViewById(R.id.contact_diverged);
            danger.setImageTintList(
                    android.content.res.ColorStateList.valueOf(dangerColor()));
            danger.setVisibility(!selectionMode && isDiverged ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.contact_end_slot)
                    .setVisibility(selectionMode || isDiverged ? View.VISIBLE : View.GONE);

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

    /** The single selected merged row, or null when several are. */
    private Group selectedGroup() {
        Group selected = null;
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(groupKey(group))) {
                if (selected != null) {
                    return null;
                }
                selected = group;
            }
        }
        return selected;
    }

    /** The subscribed addressbooks the group does not already live in. */
    private List<BookEntry> booksOutside(Group group) {
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Entry entry : group.replicas) {
            current.add(entry.book.url);
        }

        List<BookEntry> books = new ArrayList<>();
        for (BookEntry entry : base.loadSubscribedAddressbooks()) {
            if (!current.contains(entry.book.url)) {
                books.add(entry);
            }
        }
        return books;
    }

    /** One two-line label per addressbook of a picker dialog. */
    private CharSequence[] bookLabels(List<BookEntry> books) {
        CharSequence[] labels = new CharSequence[books.size()];
        for (int index = 0; index < books.size(); index++) {
            BookEntry entry = books.get(index);
            labels[index] = bookLabel(entry.book.name, entry.accountEmail);
        }
        return labels;
    }

    /**
     * A two-line addressbook label: the name at full size over the
     * account email, diminished and secondary (same styling as the
     * auth config options), so long names and emails never wrap into
     * one unreadable line.
     */
    private CharSequence bookLabel(String name, String email) {
        android.text.SpannableString label = new android.text.SpannableString(name + "\n" + email);
        int start = name.length() + 1;
        label.setSpan(
                new android.text.style.RelativeSizeSpan(0.8f),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(
                new android.text.style.ForegroundColorSpan(
                        resolveColor(android.R.attr.textColorSecondary)),
                start,
                label.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /** Stages a copied card into the target addressbook. */
    private void createCopy(BookEntry target, AccountEntry account, String id, String vcard) {
        String key = cardKey(account.account, target.book.url, id);

        // Google creates land in myContacts; the group membership
        // pushes right after the create (the key rename of confirmPush
        // carries it over).
        if (account.account.baseUrl.startsWith(CardamumClient.GOOGLE_PREFIX)
                && !"myContacts".equals(target.book.id)) {
            base.stageMembership(target.accountEmail, key, target.book.url, true);
        }
        base.saveLocal(target.accountEmail, key, target.book.url, new Card(id, null, null, vcard));
    }

    /** The selected rows' replicas, in list order. */
    private List<Entry> selectedReplicas() {
        List<Entry> replicas = new ArrayList<>();
        for (Group group : sortedContacts) {
            if (selectedKeys.contains(groupKey(group))) {
                replicas.addAll(group.replicas);
            }
        }
        return replicas;
    }

    /**
     * Merges the selected rows into one single card through the merge
     * form: the user picks the addressbook whose copy remains (skipped
     * when every card shares one addressbook), the union of the
     * documents prefills the editor, and saving stages the merged
     * content onto the surviving card and a delete for every other.
     */
    private void mergeSelected() {
        mergeReplicas(selectedReplicas());
    }

    /** The survivor chooser over any replica list, then the merge. */
    private void mergeReplicas(List<Entry> replicas) {
        if (distinctRefs(replicas).size() < 2) {
            return;
        }

        // The first replica of each addressbook is its candidate
        // survivor; one book means there is nothing to choose.
        Map<String, Entry> byBook = new java.util.LinkedHashMap<>();
        for (Entry entry : replicas) {
            byBook.putIfAbsent(entry.book.url, entry);
        }

        List<Entry> candidates = new ArrayList<>(byBook.values());
        if (candidates.size() == 1) {
            performMerge(replicas, candidates.get(0));
            return;
        }

        CharSequence[] labels = new CharSequence[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            Entry entry = candidates.get(index);
            labels[index] = bookLabel(entry.book.name, entry.accountEmail);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.merge_target_title)
                .setItems(
                        labels,
                        (dialog, which) -> performMerge(replicas, candidates.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Merges into the survivor: a straight absorb when every card
     * already carries the same content (the cards of one merged
     * contact, nothing to reconcile), the merge form otherwise.
     */
    private void performMerge(List<Entry> replicas, Entry survivor) {
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            hashes.add(entry.hash);
        }

        if (hashes.size() == 1) {
            mergeDirect(replicas, survivor);
        } else {
            openMergeForm(replicas, survivor);
        }
    }

    /** Absorbs identical cards into the survivor, no form needed. */
    private void mergeDirect(List<Entry> replicas, Entry survivor) {
        exitSelection();

        String survivorRef = replicaRefOf(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            String ref = replicaRefOf(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            base.markDeleted(
                    entry.accountEmail,
                    cardKey(owner.account, entry.book.url, entry.card.id),
                    entry.card);
        }

        toast(getString(R.string.merge_done));
        reloadContacts();
    }

    /** Opens the merge form with a surviving replica armed. */
    private void openMergeForm(List<Entry> replicas, Entry survivor) {
        exitSelection();
        if (openMerged(replicas)) {
            mergeSurvivor = survivor;
        }
    }

    /** The distinct physical cards behind a replica list, as refs. */
    private java.util.Set<String> distinctRefs(List<Entry> replicas) {
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            refs.add(replicaRefOf(entry));
        }
        return refs;
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

    /** Clears the search query (the bar itself is always visible). */
    /** Swaps the title for the search pill and opens the keyboard. */
    private void openSearch() {
        searchOpen = true;
        findViewById(R.id.bar_title).setVisibility(View.GONE);
        findViewById(R.id.contacts_bar_spacer).setVisibility(View.GONE);
        findViewById(R.id.contacts_search).setVisibility(View.GONE);
        findViewById(R.id.contacts_search_pill).setVisibility(View.VISIBLE);
        findViewById(R.id.contacts_search_close).setVisibility(View.VISIBLE);

        EditText input = findViewById(R.id.contacts_search_input);
        input.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, 0);
    }

    /** Clears the query and gives the title its place back. */
    private void closeSearch() {
        ((EditText) findViewById(R.id.contacts_search_input)).setText("");
        if (!searchOpen) {
            return;
        }
        searchOpen = false;

        hideKeyboard();
        // The chrome only moves when the contacts screen shows it (a
        // sync landing while another screen is open also ends here).
        if (screen != PANEL_CONTACTS) {
            return;
        }
        findViewById(R.id.contacts_search_pill).setVisibility(View.GONE);
        findViewById(R.id.contacts_search_close).setVisibility(View.GONE);
        findViewById(R.id.bar_title).setVisibility(View.VISIBLE);
        findViewById(R.id.contacts_bar_spacer).setVisibility(View.VISIBLE);
        findViewById(R.id.contacts_search)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
    }

    /**
     * The contacts screen's chrome, per selection and search state (the
     * bar is shared across screens, so this only runs while the
     * contacts screen shows; applyChrome re-runs it on every return).
     */
    private void updateSelectionUi() {
        if (screen != PANEL_CONTACTS) {
            return;
        }

        // The selected count takes the title's spot, so an open search
        // gives way (and its query with it).
        if (selectionMode) {
            closeSearch();
        }

        TextView title = findViewById(R.id.bar_title);
        if (selectionMode) {
            title.setText(getString(R.string.selected_count, selectedKeys.size()));
        } else {
            title.setText(R.string.contacts_title);
        }
        title.setVisibility(searchOpen ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_bar_spacer)
                .setVisibility(searchOpen ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_search_pill)
                .setVisibility(searchOpen ? View.VISIBLE : View.GONE);
        findViewById(R.id.contacts_search_close)
                .setVisibility(searchOpen ? View.VISIBLE : View.GONE);

        findViewById(R.id.contacts_search)
                .setVisibility(selectionMode || searchOpen ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_menu).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.contacts_close)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // Merging needs at least two physical cards (one merged row
        // already offers that).
        findViewById(R.id.contacts_merge)
                .setVisibility(
                        selectionMode && distinctRefs(selectedReplicas()).size() > 1
                                ? View.VISIBLE
                                : View.GONE);
        findViewById(R.id.contacts_delete)
                .setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        // The whole sync slot goes, not just the inner button: an empty
        // 48dp frame would push the selection icons off the edge.
        findViewById(R.id.contacts_sync_slot)
                .setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.fab).setVisibility(selectionMode ? View.GONE : View.VISIBLE);
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

    /** Opens the edit form on a fresh card in the target addressbook. */
    private void openNewContact(Addressbook book, String accountEmail) {
        editingBook = book;
        editingAccountEmail = accountEmail;
        editingCard = null;
        editingReplicas = new ArrayList<>();
        mergeSurvivor = null;
        pendingBookState = null;
        advancedVcard = null;
        advancedDirty = false;
        editingVcard = newVcard();

        editingTitle = getString(R.string.contact_new);
        advancedAvailable = true;

        form.load(null, null, null);
        show(PANEL_CONTACT);
    }

    /**
     * Opens the edit form on a merged contact: the single document when
     * the replicas agree, their union with per-field alternative chips
     * when they diverge. Saving stages the form onto every replica.
     * Returns false when the documents could not be read.
     */
    private boolean openMerged(List<Entry> replicas) {
        Entry primary = replicas.get(0);
        editingBook = primary.book;
        editingAccountEmail = primary.accountEmail;
        editingCard = primary.card;
        editingReplicas = new ArrayList<>(replicas);
        mergeSurvivor = null;
        pendingBookState = null;
        advancedVcard = null;
        advancedDirty = false;

        // The distinct documents behind the group: one per normalized
        // content hash. One document means a plain edit; several go
        // through the bridge's union merge.
        List<String> docs = new ArrayList<>();
        java.util.Set<String> hashes = new java.util.HashSet<>();
        for (Entry entry : replicas) {
            if (hashes.add(entry.hash)) {
                docs.add(entry.card.vcard);
            }
        }

        JSONObject model;
        JSONObject alternatives = null;
        org.json.JSONArray changed = null;
        try {
            if (docs.size() == 1) {
                editingVcard = primary.card.vcard;
                model = client.projectCard(primary.card);
            } else {
                JSONObject merged = client.mergeCards(docs);
                editingVcard = merged.optString("vcard");
                model = merged.optJSONObject("model");
                alternatives = merged.optJSONObject("alternatives");
                // A non-null changed set turns on the form's conflict
                // mode: only the disagreeing fields show.
                changed = merged.optJSONArray("changed");
                if (changed == null) {
                    changed = new org.json.JSONArray();
                }
            }
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return false;
        }

        editingTitle = displayName(primary);
        // Raw lines cannot fan out to several physical documents, so
        // the advanced editor only opens on single-card contacts.
        advancedAvailable = distinctRefs(replicas).size() == 1;

        form.load(model, alternatives, changed);
        show(PANEL_CONTACT);
        return true;
    }

    private void setUpContactPanel() {
        findViewById(R.id.contact_books).setOnClickListener(view -> manageBooks());
        findViewById(R.id.contact_advanced).setOnClickListener(view -> openAdvanced());
    }

    /**
     * Leaves the editor without saving. The list still reloads: the
     * addressbooks dialog stages copies and removals it must reflect.
     * An unsaved Merge is abandoned.
     */
    private void closeContact() {
        mergeSurvivor = null;
        pendingBookState = null;
        advancedVcard = null;
        advancedDirty = false;
        reloadContacts();
        showBack(PANEL_CONTACTS);
    }

    // ---- Advanced editor ----------------------------------------------------

    /**
     * Opens the raw-property editor on the working document, the form's
     * current state baked in first so both views agree. Only offered on
     * single-card contacts (and new ones): raw lines cannot fan out to
     * several physical documents.
     */
    private void openAdvanced() {
        try {
            String working = advancedVcard != null ? advancedVcard : editingVcard;
            advancedVcard = client.applyCard(working, form.collect());
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
            return;
        }
        show(PANEL_ADVANCED);
    }

    /** Back to the form, rebuilt from the edited document. */
    private void closeAdvanced() {
        try {
            form.load(client.projectCard(advancedVcard), null, null);
        } catch (Exception error) {
            showError(error, R.string.contact_open_failed);
        }
        showBack(PANEL_CONTACT);
    }

    /** Rebuilds the property rows and the closing source block. */
    private void renderAdvanced() {
        LinearLayout container = findViewById(R.id.advanced_container);
        container.removeAllViews();

        org.json.JSONArray props = client.cardProps(advancedVcard);
        for (int index = 0; props != null && index < props.length(); index++) {
            int at = index;
            String line = props.optString(index);
            container.addView(
                    propertyRow(
                            propertyName(line),
                            line,
                            () -> advancedDialog(at, line)));
        }

        TextView add = new TextView(this);
        add.setText(R.string.advanced_add);
        add.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        add.setTextSize(14);
        LinearLayout addRow = new LinearLayout(this);
        addRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        addRow.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        addRow.setOnClickListener(view -> advancedDialog(-1, ""));
        addRow.addView(add);
        container.addView(addRow);

        TextView header = new TextView(this);
        header.setText(R.string.advanced_source);
        header.setTextColor(resolveColor(android.R.attr.colorAccent));
        header.setTextSize(15);
        header.setPadding(dp(16), dp(24), dp(16), dp(4));
        container.addView(header);

        TextView source = new TextView(this);
        source.setText(advancedVcard);
        source.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        source.setTypeface(android.graphics.Typeface.MONOSPACE);
        source.setTextSize(12);
        source.setPadding(dp(16), 0, dp(16), 0);
        container.addView(source);
    }

    /** A tappable property row: the name over the whole raw line. */
    private View propertyRow(String title, String line, Runnable onClick) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        titleView.setTextSize(16);

        TextView lineView = new TextView(this);
        lineView.setText(line);
        lineView.setTextColor(resolveColor(android.R.attr.textColorSecondary));
        lineView.setTypeface(android.graphics.Typeface.MONOSPACE);
        lineView.setTextSize(12);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.setOnClickListener(view -> onClick.run());
        row.addView(titleView);
        row.addView(lineView);
        return row;
    }

    /** Edits one raw line: OK rewrites, Remove drops, blank OK drops. */
    private void advancedDialog(int index, String line) {
        EditText input = new EditText(this);
        input.setText(line);
        input.setHint(R.string.advanced_hint);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), 0);
        content.addView(input);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this)
                        .setTitle(
                                index < 0
                                        ? getString(R.string.advanced_add)
                                        : propertyName(line))
                        .setView(content)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) ->
                                        applyAdvanced(index, input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, null);
        if (index >= 0) {
            builder.setNeutralButton(
                    R.string.remove_row, (dialog, which) -> applyAdvanced(index, ""));
        }
        builder.show();
    }

    private void applyAdvanced(int index, String line) {
        try {
            advancedVcard = client.cardSetProp(advancedVcard, index, line);
            advancedDirty = true;
            renderAdvanced();
        } catch (Exception error) {
            showError(error, R.string.advanced_invalid);
        }
    }

    /** The line's property name: everything before the parameters. */
    private static String propertyName(String line) {
        int cut = line.length();
        int colon = line.indexOf(':');
        if (colon >= 0) {
            cut = colon;
        }
        int semi = line.indexOf(';');
        if (semi >= 0 && semi < cut) {
            cut = semi;
        }
        return line.substring(0, cut);
    }

    /**
     * The addressbooks dialog of the open contact, the one placement
     * gesture: checked means the contact exists there, through any of
     * its cards. The dialog only records the desired state (reopening
     * shows it); everything applies on SAVE: a staged membership when
     * the contact already has a card on that account-level backend, a
     * copied card sharing the vCard UID anywhere else, a membership
     * removal or the card's staged delete on uncheck. The contact must
     * keep at least one addressbook (removing it everywhere is Delete).
     */
    private void manageBooks() {
        if (editingReplicas.isEmpty()) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : editingReplicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        List<BookEntry> books = base.loadSubscribedAddressbooks();
        boolean[] checked = new boolean[books.size()];
        for (int index = 0; index < books.size(); index++) {
            String url = books.get(index).book.url;
            Boolean pending = pendingBookState == null ? null : pendingBookState.get(url);
            checked[index] = pending != null ? pending : replicaByBook.containsKey(url);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_books_title)
                .setMultiChoiceItems(
                        bookLabels(books),
                        checked,
                        (dialog, which, isChecked) -> {
                            if (!isChecked && checkedCount(checked) == 0) {
                                ((AlertDialog) dialog)
                                        .getListView()
                                        .setItemChecked(which, true);
                                checked[which] = true;
                                toast(getString(R.string.manage_books_locked));
                            }
                        })
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            pendingBookState = new HashMap<>();
                            for (int index = 0; index < books.size(); index++) {
                                pendingBookState.put(books.get(index).book.url, checked[index]);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int checkedCount(boolean[] checked) {
        int count = 0;
        for (boolean state : checked) {
            if (state) {
                count++;
            }
        }
        return count;
    }

    /**
     * Applies the addressbooks dialog's desired state on save: staged
     * memberships, copies (carrying the just-saved content), removals.
     */
    private void applyBookState(String vcard) {
        Map<String, Boolean> desired = pendingBookState;
        pendingBookState = null;
        if (desired == null) {
            return;
        }

        Map<String, Entry> replicaByBook = new HashMap<>();
        for (Entry entry : editingReplicas) {
            replicaByBook.put(entry.book.url, entry);
        }

        for (BookEntry target : base.loadSubscribedAddressbooks()) {
            Boolean wanted = desired.get(target.book.url);
            if (wanted == null || wanted == replicaByBook.containsKey(target.book.url)) {
                continue;
            }
            AccountEntry account = accountFor(target.accountEmail);
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
            for (Entry entry : editingReplicas) {
                if (entry.accountEmail.equals(target.accountEmail)) {
                    base.stageMembership(
                            entry.accountEmail,
                            cardKey(account.account, entry.book.url, entry.card.id),
                            target.book.url,
                            true);
                    return;
                }
            }
        }

        String uid = cardIndex(vcard).optString("uid");
        String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
        createCopy(target, account, id, vcard);
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
        AccountEntry owner = accountFor(replica.accountEmail);
        if (owner == null) {
            return;
        }
        String replicaKey = cardKey(owner.account, replica.book.url, replica.card.id);

        if (CardamumClient.isAccountLevel(owner.account)
                && base.loadMemberships(replica.accountEmail, replicaKey).size() > 1) {
            // The card stays, only this membership goes.
            base.stageMembership(replica.accountEmail, replicaKey, target.book.url, false);
        } else {
            // Last place of this card: the card goes.
            base.markDeleted(replica.accountEmail, replicaKey, replica.card);
        }
    }

    /**
     * Stages the edited contact in the base (offline first: nothing
     * touches the network until the next sync pushes it) and returns to
     * the list. The form model applies to every replica's own document
     * (docs/merged-view.md): each keeps its UID and unmanaged
     * properties while the managed content converges; replicas already
     * carrying the resulting content are skipped.
     */
    private void saveContact() {
        String birthday = form.birthday();
        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            toast(getString(R.string.invalid_birthday));
            return;
        }
        String anniversary = form.anniversary();
        if (!anniversary.isEmpty() && !anniversary.matches("\\d{4}-\\d{2}-\\d{2}")) {
            toast(getString(R.string.invalid_anniversary));
            return;
        }

        try {
            JSONObject model = form.collect();

            if (editingCard == null) {
                AccountEntry account = accountFor(editingAccountEmail);
                if (account == null) {
                    // The account was deleted while the editor was open.
                    toast(getString(R.string.save_failed));
                    return;
                }
                String source = advancedVcard != null ? advancedVcard : editingVcard;
                String vcard = client.applyCard(source, model);
                String uid = cardIndex(vcard).optString("uid");
                String id = uid.isEmpty() ? UUID.randomUUID().toString() : uid;
                base.saveLocal(
                        editingAccountEmail,
                        cardKey(account.account, editingBook.url, id),
                        editingBook.url,
                        new Card(id, null, null, vcard));
            } else if (mergeSurvivor != null) {
                saveMerge(model);
            } else {
                saveFanOut(model);
            }

            // The addressbooks dialog's choices apply here too, the
            // copies carrying the just-saved content.
            if (editingCard != null && pendingBookState != null) {
                applyBookState(client.applyCard(editingCard.vcard, model));
            }
        } catch (Exception error) {
            showError(error, R.string.save_failed);
            return;
        }

        reloadContacts();
        showBack(PANEL_CONTACTS);
    }

    /**
     * The fan-out save: the form model applies to every card's own
     * document.
     */
    private void saveFanOut(JSONObject model) {
        java.util.Set<String> staged = new java.util.HashSet<>();

        for (Entry entry : editingReplicas) {
            // An m:n replica appears once per book; stage it once.
            if (!staged.add(replicaRefOf(entry))) {
                continue;
            }
            AccountEntry account = accountFor(entry.accountEmail);
            if (account == null) {
                continue;
            }

            // The advanced editor only opens on single-card contacts,
            // so its document stands in for the card's; a raw edit must
            // stage even when the managed-content hash is unchanged.
            String source = advancedVcard != null ? advancedVcard : entry.card.vcard;
            String vcard = client.applyCard(source, model);
            if (!advancedDirty && cardIndex(vcard).optString("hash").equals(entry.hash)) {
                continue;
            }
            base.saveLocal(
                    entry.accountEmail,
                    cardKey(account.account, entry.book.url, entry.card.id),
                    entry.book.url,
                    new Card(entry.card.id, entry.card.uri, entry.card.etag, vcard));
        }
    }

    /**
     * The merge save: the form model applies to the surviving card and
     * every other card behind the form stages a delete.
     */
    private void saveMerge(JSONObject model) {
        Entry survivor = mergeSurvivor;
        mergeSurvivor = null;

        AccountEntry account = accountFor(survivor.accountEmail);
        if (account == null) {
            toast(getString(R.string.save_failed));
            return;
        }

        String vcard = client.applyCard(survivor.card.vcard, model);
        if (!cardIndex(vcard).optString("hash").equals(survivor.hash)) {
            base.saveLocal(
                    survivor.accountEmail,
                    cardKey(account.account, survivor.book.url, survivor.card.id),
                    survivor.book.url,
                    new Card(
                            survivor.card.id,
                            survivor.card.uri,
                            survivor.card.etag,
                            vcard));
        }

        String survivorRef = replicaRefOf(survivor);
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (Entry entry : editingReplicas) {
            String ref = replicaRefOf(entry);
            if (ref.equals(survivorRef) || !removed.add(ref)) {
                continue;
            }
            AccountEntry owner = accountFor(entry.accountEmail);
            if (owner == null) {
                continue;
            }
            base.markDeleted(
                    entry.accountEmail,
                    cardKey(owner.account, entry.book.url, entry.card.id),
                    entry.card);
        }

        toast(getString(R.string.merge_done));
    }

    /** Rebuilds the contacts list from the base (staged edits included). */
    private void reloadContacts() {
        contacts = loadEntries();
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

    /** Navigates forward: the panels slide in from the right. */
    private void show(int panel) {
        show(panel, false);
    }

    /** Navigates back: the panels slide in from the left. */
    private void showBack(int panel) {
        show(panel, true);
    }

    private void show(int panel, boolean back) {
        show(
                panel,
                back ? R.anim.slide_in_left : R.anim.slide_in_right,
                back ? R.anim.slide_out_right : R.anim.slide_out_left);
    }

    private void show(int panel, int inAnim, int outAnim) {
        hideKeyboard();
        flipper.setInAnimation(this, inAnim);
        flipper.setOutAnimation(this, outAnim);
        flipper.setDisplayedChild(panel);
        screen = panel;
        applyChrome(panel);
    }

    /**
     * Slides a whole-frame overlay (its own bar included) over the
     * current screen: the addressbooks drawer from the left, the auth
     * sheet from the bottom. What is underneath stays put.
     */
    private void openOverlay(int panel) {
        hideKeyboard();
        View overlay =
                findViewById(panel == PANEL_HOME ? R.id.overlay_home : R.id.overlay_auth);
        overlay.setVisibility(View.VISIBLE);
        overlay.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(
                        this,
                        panel == PANEL_HOME ? R.anim.slide_in_left : R.anim.slide_in_up));
        screen = panel;
        applyChrome(panel);
    }

    /** Slides a whole-frame overlay back out, then hides it. */
    private void closeOverlay(int panel) {
        hideKeyboard();
        View overlay =
                findViewById(panel == PANEL_HOME ? R.id.overlay_home : R.id.overlay_auth);
        android.view.animation.Animation exit =
                android.view.animation.AnimationUtils.loadAnimation(
                        this,
                        panel == PANEL_HOME ? R.anim.slide_out_left : R.anim.slide_out_down);
        exit.setAnimationListener(
                new android.view.animation.Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationRepeat(android.view.animation.Animation animation) {}

                    @Override
                    public void onAnimationEnd(android.view.animation.Animation animation) {
                        overlay.setVisibility(View.GONE);
                    }
                });
        overlay.startAnimation(exit);
    }

    /**
     * Configures the persistent app bar and FAB for the screen: every
     * chrome element goes off, then the screen's own set comes back
     * (the contacts screen delegates to its selection/search state).
     */
    private void applyChrome(int panel) {
        android.widget.ImageButton fab = findViewById(R.id.fab);

        // The overlays carry their own bars; only the FAB is shared.
        if (panel == PANEL_AUTH) {
            // The steps carry their own continue FABs (the email one
            // rides in a row with its field).
            fab.setVisibility(View.GONE);
            applyAuthChrome();
            return;
        }
        if (panel == PANEL_HOME) {
            fab.setImageResource(R.drawable.ic_add);
            fab.setContentDescription(getString(R.string.add_account));
            fab.setVisibility(View.VISIBLE);
            return;
        }

        for (int id :
                new int[] {
                    R.id.bar_back,
                    R.id.contacts_menu,
                    R.id.contacts_close,
                    R.id.contacts_search_pill,
                    R.id.contacts_search_close,
                    R.id.contacts_search,
                    R.id.contacts_merge,
                    R.id.contacts_delete,
                    R.id.contacts_sync_slot,
                    R.id.contact_advanced,
                    R.id.contact_books,
                }) {
            findViewById(id).setVisibility(View.GONE);
        }
        findViewById(R.id.bar_title).setVisibility(View.VISIBLE);
        findViewById(R.id.contacts_bar_spacer).setVisibility(View.VISIBLE);

        TextView title = findViewById(R.id.bar_title);
        switch (panel) {
            case PANEL_CONTACTS:
                fab.setImageResource(R.drawable.ic_add);
                fab.setContentDescription(getString(R.string.contacts_add));
                fab.setVisibility(View.VISIBLE);
                updateSelectionUi();
                break;
            case PANEL_CONTACT:
                title.setText(editingTitle);
                findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                findViewById(R.id.contact_advanced)
                        .setVisibility(advancedAvailable ? View.VISIBLE : View.GONE);
                findViewById(R.id.contact_books)
                        .setVisibility(editingCard != null ? View.VISIBLE : View.GONE);
                fab.setImageResource(R.drawable.ic_check);
                fab.setContentDescription(getString(R.string.contact_save));
                fab.setVisibility(View.VISIBLE);
                break;
            case PANEL_ADVANCED:
                title.setText(R.string.advanced_title);
                findViewById(R.id.bar_back).setVisibility(View.VISIBLE);
                fab.setVisibility(View.GONE);
                break;
            default:
                break;
        }
    }

    /** The shared FAB's action, per screen. */
    private void onFabClick() {
        switch (screen) {
            case PANEL_CONTACTS:
                addContact();
                break;
            case PANEL_CONTACT:
                saveContact();
                break;
            case PANEL_HOME:
                startAuth();
                break;
            default:
                break;
        }
    }

    /** The main bar's back arrow, per screen (overlays have their own). */
    private void onBarBack() {
        switch (screen) {
            case PANEL_CONTACT:
                closeContact();
                break;
            case PANEL_ADVANCED:
                closeAdvanced();
                break;
            default:
                break;
        }
    }

    /** Resolves a theme attribute to its referenced resource id. */
    private int resolveAttr(int attr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    /**
     * The theme's standard error colour (colorError, API 26+), falling
     * back to the secondary text tone on older devices.
     */
    private int dangerColor() {
        android.util.TypedValue value = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.colorError, value, true)) {
            if (value.resourceId != 0) {
                return getResources().getColor(value.resourceId, getTheme());
            }
            return value.data;
        }
        return resolveColor(android.R.attr.textColorSecondary);
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
