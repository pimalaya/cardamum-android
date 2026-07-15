package org.pimalaya.cardamum;

import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.Addressbook;
import org.pimalaya.cardamum.client.AuthMethod;
import org.pimalaya.cardamum.client.CardamumClient;
import org.pimalaya.cardamum.client.ServiceConfig;

/**
 * The connection wizard behind the auth panel: the email (or server)
 * step with its parallel discovery, the config step proposing one
 * option per protocol and authentication variant, the credential
 * prompts, and the addressbook selection whose commit persists the
 * account and runs the first sync. The OAuth grants live in
 * {@link OauthFlow}, which lands its redeemed tokens back here through
 * {@link #connect}; the host keeps the step navigation (the flipper,
 * the bar, the shared FAB) and calls in through {@link #open},
 * {@link #continueStep} and {@link #stepReady}. Nothing persists
 * before the selection confirms, so backing out of the flow leaves
 * everything untouched.
 */
final class OnboardingFlow {
    private final MainActivity host;
    private final OauthFlow oauth;

    /** Onboarding state: the entered email and its searched configs. */
    private String pendingEmail;

    private List<ServiceConfig> searchedConfigs = new ArrayList<>();

    /** The action armed by the picked config option; null when none. */
    private Runnable selectedConfig;

    /** The setup-mode choice: standard (true), advanced (false), or
     *  still being asked (null). */
    private Boolean setupMode;

    /** A config step waiting on the setup-mode choice. */
    private Runnable pendingConfigStep;

    /** The just-connected account, held until the books step commits. */
    private AccountEntry pendingAccount;

    /** The just-connected account's addressbooks. */
    private List<Addressbook> pendingBooks;

    /** The email whose books step is open. */
    private String connectedEmail;

    /** The books step's subscribe checkboxes. */
    private List<BookChoice> bookChoices = new ArrayList<>();

    /** The books step's background-sync cadence, null when bookless. */
    private Spinner booksInterval;

    OnboardingFlow(MainActivity host, OauthFlow oauth) {
        this.host = host;
        this.oauth = oauth;
    }

    /** Resets the flow to its first step and shows it. */
    void open() {
        pendingEmail = null;
        searchedConfigs = new ArrayList<>();
        ((EditText) host.findViewById(R.id.email_input)).setText("");
        host.showAuth(MainActivity.STEP_EMAIL);
    }

    /** The shared FAB's continue action for the given auth step. */
    void continueStep(int step) {
        switch (step) {
            case MainActivity.STEP_EMAIL:
                submitEmail();
                break;
            case MainActivity.STEP_CONFIG:
                if (selectedConfig != null) {
                    selectedConfig.run();
                }
                break;
            case MainActivity.STEP_BOOKS:
                confirmBooks();
                break;
            default:
                break;
        }
    }

    /** Whether the given auth step's continue is available. */
    boolean stepReady(int step) {
        switch (step) {
            case MainActivity.STEP_EMAIL:
                return emailSubmittable();
            case MainActivity.STEP_CONFIG:
                return selectedConfig != null;
            case MainActivity.STEP_BOOKS:
                return booksAnyChecked();
            default:
                return false;
        }
    }

    /** Whether the email field holds a submittable address or URI. */
    boolean emailSubmittable() {
        String address =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        return !address.isEmpty() && !address.contains(" ");
    }

    /** The email step's continue: discovery for an address, manual for a URI. */
    private void submitEmail() {
        // The panel stays visible while the search runs; drop the
        // field's focus so the keyboard leaves.
        host.hideKeyboard();

        // One field covers every case, the CLI way: an email or a bare
        // domain goes through discovery (every mechanism is
        // domain-driven, and the provider rules ride inside it as one
        // mechanism among the others), a connection URI is a server to
        // configure by hand.
        String address =
                ((EditText) host.findViewById(R.id.email_input)).getText().toString().trim();
        pendingEmail = address;
        askSetupMode();
        if (address.contains("://")) {
            deliverConfigs(() -> showManualConfigs(address));
        } else {
            search();
        }
    }

    /**
     * Asks whether to set the account up the standard way (the first
     * proposal, every addressbook, phone mirroring, background sync
     * every 15 minutes) or step by step, while the discovery already
     * runs behind; the flow proceeds once both the choice and the
     * discovery are in.
     */
    private void askSetupMode() {
        setupMode = null;
        pendingConfigStep = null;

        new AlertDialog.Builder(host)
                .setTitle(R.string.setup_choice_title)
                .setMessage(R.string.setup_choice_message)
                .setCancelable(false)
                .setPositiveButton(R.string.setup_simple, (dialog, which) -> chooseSetup(true))
                .setNegativeButton(R.string.setup_advanced, (dialog, which) -> chooseSetup(false))
                .show();
    }

    private void chooseSetup(boolean simple) {
        setupMode = simple;
        if (pendingConfigStep != null) {
            Runnable step = pendingConfigStep;
            pendingConfigStep = null;
            step.run();
        }
    }

    /** Runs a config step now, or once the setup choice is made. */
    private void deliverConfigs(Runnable step) {
        if (setupMode == null) {
            pendingConfigStep = step;
        } else {
            step.run();
        }
    }

    /** True inside a standard (one-tap) account setup. */
    private boolean simpleSetup() {
        return setupMode == Boolean.TRUE;
    }

    /**
     * Searches the email's (or bare domain's) CardDAV and JMAP service
     * configs, then proposes them. The MX provider probe runs first and
     * is decisive: a domain whose mail exchanges live at Google or
     * Microsoft is hosted there, suite and contacts included, so the
     * dedicated provider variants show right away and the protocol
     * sweep (PACC, CardDAV and JMAP resolves), which would only produce
     * origin-fallback noise there, is skipped. No match runs the sweep
     * in parallel. A failing search surfaces as an error dialog and
     * stays on the email panel, and a domain nothing was discovered for
     * falls back to the manual server rows.
     */
    private void search() {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);

        host.io.execute(
                () -> {
                    // A null resolver falls back to the bridge's
                    // DNS-over-HTTPS default, which works on mobile
                    // networks that block outbound DNS over TCP. A
                    // failing probe just goes on to the sweep.
                    if (!emailLogin().isEmpty()) {
                        String provider = null;
                        try {
                            List<ServiceConfig> hits =
                                    host.client.searchProvider(pendingEmail, null);
                            provider = hits.isEmpty() ? null : hits.get(0).source;
                        } catch (Exception error) {
                            Log.w("cardamum", "provider probe failed", error);
                        }

                        if (provider != null) {
                            String matched = provider;
                            host.main.post(
                                    () -> {
                                        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                        deliverConfigs(() -> showProviderConfigs(matched));
                                    });
                            return;
                        }
                    }

                    List<ServiceConfig> configs = new ArrayList<>();
                    Exception failure = null;
                    try {
                        configs = host.client.searchAll(pendingEmail, null);
                    } catch (Exception error) {
                        // Failing mechanisms are already skipped inside
                        // the search; this is the whole search dying.
                        Log.w("cardamum", "config search failed", error);
                        failure = error;
                    }

                    List<ServiceConfig> found = configs;
                    Exception searchFailure = failure;
                    host.main.post(
                            () -> {
                                host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
                                if (searchFailure != null) {
                                    deliverConfigs(
                                            () ->
                                                    host.showError(
                                                            searchFailure,
                                                            R.string.discover_failed));
                                    return;
                                }
                                if (found.isEmpty() && !pendingEmail.contains("@")) {
                                    // A domain nothing was discovered
                                    // for still names a server to try
                                    // by hand.
                                    deliverConfigs(() -> showManualConfigs(pendingEmail));
                                    return;
                                }
                                searchedConfigs = found;
                                deliverConfigs(this::showConfigs);
                            });
                });
    }

    /** The config Continue back to idle: enabled once an option is picked. */
    void resetConfigContinue() {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
        host.setFabEnabled(R.id.fab, selectedConfig != null);
    }

    /**
     * Fills the config screen with the proposals matching the provider,
     * as a radio list: one option per protocol and authentication
     * variant. Picking an option reveals the Continue button, which
     * runs it: OAuth starts the browser grant, password pops the
     * credentials dialog.
     */
    private void showConfigs() {
        TextView email = host.findViewById(R.id.config_email);
        email.setText(pendingEmail);

        selectedConfig = null;
        resetConfigContinue();

        LinearLayout container = host.findViewById(R.id.config_container);
        container.removeAllViews();

        // Present the configs in preference order (JMAP, CardDAV, then
        // the rest); the sort is stable, so discovery order breaks ties.
        java.util.Collections.sort(
                searchedConfigs,
                (left, right) ->
                        Integer.compare(serviceRank(left.service), serviceRank(right.service)));
        for (ServiceConfig config : searchedConfigs) {
            addConfigItems(container, config);
        }
        boolean discovered = container.getChildCount() > 0;

        // Nothing discovered, or nothing the app can drive: say so,
        // and offer the big-provider sign-ins as a fallback chooser
        // when an email was entered. No protocol signal can tell
        // where someone's contacts live (Google publishes no CardDAV
        // SRV, no well-known, nothing); only the user knows, and the
        // sign-in itself is the real test.
        if (!discovered) {
            container.addView(
                    configItem(host.getString(R.string.discover_failed), null, null, null));
            if (!emailLogin().isEmpty()) {
                addGoogleItems(container);
                addMicrosoftItems(container);
            }
        }

        selectFirstConfig();
        // The standard setup skips the config screen and runs the armed
        // first proposal as if Continue was tapped; a discovery that
        // found nothing still shows the fallback chooser, since
        // auto-signing into a guessed provider would be wrong.
        if (simpleSetup() && discovered && selectedConfig != null) {
            selectedConfig.run();
            return;
        }
        host.showAuth(MainActivity.STEP_CONFIG);
    }

    /**
     * The config options of a provider-probe hit: the dedicated
     * variants of the matched provider, driven by the shipped OAuth
     * clients.
     */
    private void showProviderConfigs(String provider) {
        TextView email = host.findViewById(R.id.config_email);
        email.setText(pendingEmail);

        selectedConfig = null;
        resetConfigContinue();

        LinearLayout container = host.findViewById(R.id.config_container);
        container.removeAllViews();

        if (provider.equals("provider:google")) {
            addGoogleItems(container);
        } else {
            addMicrosoftItems(container);
        }

        selectFirstConfig();
        if (simpleSetup() && selectedConfig != null) {
            selectedConfig.run();
            return;
        }
        host.showAuth(MainActivity.STEP_CONFIG);
    }

    /**
     * A config option running an OAuth grant: the standard setup runs
     * the shipped flow directly (the recommended path needs no
     * confirm), everything else opens the client prompt.
     */
    private Runnable oauthOption(
            String baseUrl,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String defaultClientId,
            String defaultRedirect,
            Runnable defaultFlow) {
        return () -> {
            if (simpleSetup() && defaultFlow != null) {
                defaultFlow.run();
                return;
            }
            oauth.promptOauthClient(
                    pendingEmail,
                    baseUrl,
                    authEndpoint,
                    tokenEndpoint,
                    scope,
                    defaultClientId,
                    defaultRedirect,
                    defaultFlow);
        };
    }

    /**
     * The dedicated Google variants, one OAuth row per service
     * (CardDAV, People API); each opens the client prompt prefilled
     * with the shipped client, whose unchanged submission runs the
     * dedicated flow. Base URLs embed the entered email.
     */
    private void addGoogleItems(LinearLayout container) {
        container.addView(
                configItem(
                        host.getString(R.string.config_carddav),
                        null,
                        host.getString(R.string.config_oauth2),
                        oauthOption(
                                CardamumClient.googleCarddavBase(pendingEmail),
                                Oauth.GOOGLE_AUTH_ENDPOINT,
                                Oauth.GOOGLE_TOKEN_ENDPOINT,
                                Oauth.GOOGLE_SCOPE,
                                Oauth.GOOGLE_CLIENT_ID,
                                Oauth.GOOGLE_REDIRECT_URI,
                                () ->
                                        oauth.startGoogleOauth(
                                                pendingEmail,
                                                Oauth.GOOGLE_SCOPE,
                                                CardamumClient.googleCarddavBase(pendingEmail)))));
        container.addView(
                configItem(
                        host.getString(R.string.config_google_api),
                        null,
                        host.getString(R.string.config_oauth2),
                        oauthOption(
                                CardamumClient.googlePeopleBase(pendingEmail),
                                Oauth.GOOGLE_AUTH_ENDPOINT,
                                Oauth.GOOGLE_TOKEN_ENDPOINT,
                                Oauth.GOOGLE_PEOPLE_SCOPE,
                                Oauth.GOOGLE_CLIENT_ID,
                                Oauth.GOOGLE_REDIRECT_URI,
                                () ->
                                        oauth.startGoogleOauth(
                                                pendingEmail,
                                                Oauth.GOOGLE_PEOPLE_SCOPE,
                                                CardamumClient.googlePeopleBase(pendingEmail)))));
    }

    /**
     * The dedicated Microsoft Graph variant, one OAuth row opening the
     * client prompt prefilled with the shipped client, whose unchanged
     * submission runs the dedicated flow. The base URL embeds the
     * entered email.
     */
    private void addMicrosoftItems(LinearLayout container) {
        container.addView(
                configItem(
                        host.getString(R.string.config_msgraph),
                        null,
                        host.getString(R.string.config_oauth2),
                        oauthOption(
                                CardamumClient.msgraphBase(pendingEmail),
                                Oauth.MICROSOFT_AUTH_ENDPOINT,
                                Oauth.MICROSOFT_TOKEN_ENDPOINT,
                                Oauth.MICROSOFT_SCOPE,
                                Oauth.MICROSOFT_CLIENT_ID,
                                Oauth.MICROSOFT_REDIRECT_URI,
                                () -> oauth.startMicrosoftOauth(pendingEmail))));
    }

    /**
     * The config options for a server typed directly in the first
     * field (a host[:port], or a connection URI): no discovery, the
     * same server offered over CardDAV and JMAP, the credentials asked
     * next (an empty login sends the secret as a Bearer token).
     */
    private void showManualConfigs(String address) {
        String url = address.contains("://") ? address : "https://" + address;
        String serverHost = hostOf(url);
        String jmapUrl = CardamumClient.jmapBase(url);

        ((TextView) host.findViewById(R.id.config_email)).setText(pendingEmail);
        selectedConfig = null;
        resetConfigContinue();

        LinearLayout container = host.findViewById(R.id.config_container);
        container.removeAllViews();
        // JMAP before CardDAV, per the protocol preference order.
        container.addView(
                configItem(
                        host.getString(R.string.config_jmap),
                        serverHost,
                        host.getString(R.string.config_password),
                        () -> promptCredentials(jmapUrl, serverHost, emailLogin())));
        container.addView(
                configItem(
                        host.getString(R.string.config_carddav),
                        serverHost,
                        host.getString(R.string.config_password),
                        () -> promptCredentials(url, serverHost, emailLogin())));

        selectFirstConfig();
        if (simpleSetup() && selectedConfig != null) {
            selectedConfig.run();
            return;
        }
        host.showAuth(MainActivity.STEP_CONFIG);
    }

    /**
     * The entered email as a login prefill; empty when the connection
     * field carried a bare domain or a URL instead.
     */
    private String emailLogin() {
        return pendingEmail != null && pendingEmail.contains("@") ? pendingEmail : "";
    }

    /**
     * Prompts for the login and secret of a server. The login is only
     * prefilled (from the discovered username or the entered email): a
     * provider's login is not necessarily the address, so the user
     * confirms it; leaving it empty sends the secret as a Bearer token.
     * Verifies the account on submit.
     */
    private void promptCredentials(String baseUrl, String serverHost, String prefilledLogin) {
        EditText login = host.ui.field(R.string.custom_login, prefilledLogin);
        EditText secret = new EditText(host);
        secret.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint(R.string.password_hint);

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        fields.addView(login);
        fields.addView(secret);

        new AlertDialog.Builder(host)
                .setTitle(R.string.password_title)
                .setMessage(host.getString(R.string.password_server, pendingEmail, serverHost))
                .setView(fields)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String password = secret.getText().toString();
                            if (password.isEmpty()) {
                                host.toast(host.getString(R.string.password_empty));
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
     * One radio option per usable authentication variant of a searched
     * config: password variants prompt for the credentials, OAuth code
     * grants prompt for a custom client (the endpoints prefilled from
     * the discovery). Variants the app cannot drive (no client to use)
     * are not offered at all.
     */
    private void addConfigItems(LinearLayout container, ServiceConfig config) {
        String protocol = protocolName(config.service);
        String serverHost = hostOf(config.url);
        String baseUrl =
                "jmap".equals(config.service)
                        ? CardamumClient.jmapBase(config.url)
                        : config.url;

        // Offer the auth variants in preference order (OAuth 2.0, then
        // API token, then password).
        List<AuthMethod> methods = new ArrayList<>(config.auth);
        java.util.Collections.sort(
                methods,
                (left, right) -> Integer.compare(authRank(left.type), authRank(right.type)));
        for (AuthMethod method : methods) {
            switch (method.type) {
                case PASSWORD:
                    // The discovered username (or the entered email)
                    // is only a default: a provider's login is not
                    // always the address, and a bare-domain search
                    // carries none at all, so the prompt asks the
                    // user to confirm it.
                    String login = config.username != null ? config.username : emailLogin();
                    container.addView(
                            configItem(
                                    protocol,
                                    serverHost,
                                    host.getString(R.string.config_password),
                                    () -> promptCredentials(baseUrl, serverHost, login)));
                    break;
                case BEARER:
                    // No login asked: an API token carries its own
                    // identity, and the empty login makes the
                    // backends send it as Bearer instead of Basic.
                    container.addView(
                            configItem(
                                    protocol,
                                    serverHost,
                                    host.getString(R.string.config_token),
                                    () -> promptToken(baseUrl, serverHost)));
                    break;
                case OAUTH_AUTHORIZATION_CODE_GRANT:
                    container.addView(
                            configItem(
                                    protocol,
                                    serverHost,
                                    host.getString(R.string.config_oauth2),
                                    oauthOption(
                                            baseUrl,
                                            method.authorizationEndpoint,
                                            method.tokenEndpoint,
                                            method.scope,
                                            null,
                                            null,
                                            null)));
                    break;
                case OAUTH_ISSUER:
                    // The server advertised only an issuer: discover
                    // its metadata and, when it allows dynamic client
                    // registration (RFC 7591), run the grant with no
                    // pre-registered client at all. The config's plain
                    // endpoint URL rides along as the RFC 8707
                    // resource of the grant.
                    container.addView(
                            configItem(
                                    protocol,
                                    serverHost,
                                    host.getString(R.string.config_oauth2),
                                    () ->
                                            oauth.startIssuerOauth(
                                                    pendingEmail,
                                                    baseUrl,
                                                    method.issuer,
                                                    config.url)));
                    break;
                default:
                    // Nothing the app can drive; not offered.
                    break;
            }
        }
    }

    /** Protocol precedence: JMAP over CardDAV over anything proprietary. */
    private static int serviceRank(String service) {
        switch (service) {
            case "jmap":
                return 0;
            case "carddav":
                return 1;
            default:
                return 2;
        }
    }

    /** Auth precedence: OAuth 2.0 over API token over password. */
    private static int authRank(AuthMethod.Type type) {
        switch (type) {
            case OAUTH_AUTHORIZATION_CODE_GRANT:
            case OAUTH_DEVICE_AUTHORIZATION_GRANT:
            case OAUTH_ISSUER:
                return 0;
            case BEARER:
                return 1;
            default:
                return 2;
        }
    }

    /**
     * Arms the first offerable option, so the config screen opens with
     * a sane default already picked (the highest-ranked protocol and
     * auth, per the sort applied before rendering).
     */
    private void selectFirstConfig() {
        LinearLayout container = host.findViewById(R.id.config_container);
        for (int at = 0; at < container.getChildCount(); at++) {
            View child = container.getChildAt(at);
            if (child instanceof android.widget.RadioButton && child.isEnabled()) {
                child.performClick();
                break;
            }
        }
    }

    /** The user-facing name of a searched service kind. */
    private String protocolName(String service) {
        switch (service) {
            case "carddav":
                return host.getString(R.string.config_carddav);
            case "jmap":
                return host.getString(R.string.config_jmap);
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
        android.widget.RadioButton option = new android.widget.RadioButton(host);
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
                            host.ui.resolveColor(android.R.attr.textColorSecondary)),
                    start,
                    text.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            option.setText(text);
        }

        option.setTextSize(15);
        option.setPadding(host.ui.dp(8), host.ui.dp(12), host.ui.dp(8), host.ui.dp(12));

        if (action == null) {
            option.setEnabled(false);
        } else {
            option.setOnClickListener(
                    view -> {
                        selectedConfig = action;
                        host.setFabEnabled(R.id.fab, true);
                    });
        }

        return option;
    }

    /**
     * Prompts for an API token in a dialog. No login is asked: a token
     * carries its own identity, and the empty login makes the backends
     * send it as Bearer instead of Basic. Verifies and persists the
     * account on submit.
     */
    private void promptToken(String baseUrl, String serverHost) {
        EditText input = new EditText(host);
        input.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.config_token);

        // Inset the field from the dialog edges (setView is flush).
        LinearLayout wrapper = new LinearLayout(host);
        wrapper.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        wrapper.addView(
                input,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(host)
                .setTitle(R.string.password_title)
                .setMessage(host.getString(R.string.password_server, pendingEmail, serverHost))
                .setView(wrapper)
                .setPositiveButton(
                        R.string.password_submit,
                        (dialog, which) -> {
                            String token = input.getText().toString();
                            if (token.isEmpty()) {
                                host.toast(host.getString(R.string.password_empty));
                                return;
                            }
                            connect(
                                    new Account(baseUrl, "", token),
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
    void connect(
            Account candidate,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);

        host.io.execute(
                () -> {
                    try {
                        List<Addressbook> fetched = host.client.listAddressbooks(candidate);
                        host.main.post(
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
                                    if (simpleSetup()) {
                                        confirmAllBooks(email);
                                    } else {
                                        openBooksSelection(email, fetched);
                                    }
                                });
                    } catch (Exception error) {
                        Log.w("cardamum", "connect failed", error);
                        host.main.post(
                                () -> {
                                    resetConfigContinue();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /**
     * Lists the just-connected account's addressbooks as a plain
     * checkbox per book, its name the label, subscribed by default.
     * Phone-contacts mirroring is turned on by default for every
     * subscribed book; the drawer's per-book settings let the user turn
     * it off later. Below the list, one cadence dropdown seeds the
     * background sync of every selected book (never by default). The
     * checkbox box sits at the panel's 24dp inset, aligned with the
     * title and paragraph above. Same chrome as the config panel.
     */
    private void openBooksSelection(String email, List<Addressbook> books) {
        connectedEmail = email;
        bookChoices = new ArrayList<>();
        booksInterval = null;

        LinearLayout container = host.findViewById(R.id.books_container);
        container.removeAllViews();

        if (books.isEmpty()) {
            TextView empty = new TextView(host);
            empty.setText(R.string.books_none);
            empty.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            empty.setPadding(0, host.ui.dp(16), 0, host.ui.dp(16));
            container.addView(empty);
        }

        for (Addressbook book : books) {
            boolean first = container.getChildCount() == 0;

            CheckBox subscribe = new CheckBox(host);
            subscribe.setText(book.name);
            subscribe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            subscribe.setTextColor(host.ui.resolveColor(android.R.attr.textColorPrimary));
            subscribe.setChecked(true);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = first ? host.ui.dp(8) : host.ui.dp(4);
            subscribe.setLayoutParams(params);
            subscribe.setOnCheckedChangeListener((view, checked) -> updateBooksContinue());
            container.addView(subscribe);

            bookChoices.add(new BookChoice(book.url, subscribe));
        }

        // The background sync cadence of the selected books, one choice
        // for the whole step; the drawer's per-book settings tune it
        // later, book by book.
        if (!books.isEmpty()) {
            TextView cadence = new TextView(host);
            cadence.setText(R.string.book_background_sync);
            cadence.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            cadence.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams cadenceParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            cadenceParams.topMargin = host.ui.dp(24);
            container.addView(cadence, cadenceParams);

            booksInterval = host.intervalSpinner();
            LinearLayout.LayoutParams intervalParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            intervalParams.topMargin = host.ui.dp(8);
            container.addView(booksInterval, intervalParams);
        }

        host.setAuthLoading(R.id.fab, R.id.fab_progress, false);
        updateBooksContinue();
        host.showAuth(MainActivity.STEP_BOOKS);
    }

    /** Continue is enabled only while at least one addressbook is on. */
    private void updateBooksContinue() {
        host.setFabEnabled(R.id.fab, booksAnyChecked());
    }

    private boolean booksAnyChecked() {
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                return true;
            }
        }
        return false;
    }

    /** The books step's Continue: commits the checked selection. */
    private void confirmBooks() {
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (BookChoice choice : bookChoices) {
            if (choice.subscribe.isChecked()) {
                subscribed.add(choice.url);
            }
        }
        long minutes =
                booksInterval == null
                        ? 0
                        : BackgroundSync.INTERVAL_MINUTES[booksInterval.getSelectedItemPosition()];
        commitBooks(subscribed, minutes);
    }

    /**
     * The standard setup's commit, selection-free: every addressbook
     * subscribed with phone mirroring and a 15-minute background sync,
     * then the first sync straight to the contacts list.
     */
    private void confirmAllBooks(String email) {
        connectedEmail = email;
        java.util.Set<String> subscribed = new java.util.HashSet<>();
        for (Addressbook book : pendingBooks) {
            subscribed.add(book.url);
        }
        commitBooks(subscribed, 15);
    }

    /**
     * The flow's real commit, shared by the books step's Continue and
     * the standard setup: persists the connected account and its
     * addressbooks, subscribes the given ones with phone mirroring on
     * by default and the given background sync cadence, asks the
     * permissions that setup needs (contacts, plus notifications when a
     * cadence is on), then runs the account's first sync.
     */
    private void commitBooks(java.util.Set<String> subscribed, long minutes) {
        host.accounts.removeIf(entry -> entry.email.equals(connectedEmail));
        host.accounts.add(pendingAccount);
        host.store.add(pendingAccount);
        pendingAccount = null;
        host.base.replaceAddressbooks(connectedEmail, pendingBooks);

        for (Addressbook book : pendingBooks) {
            boolean on = subscribed.contains(book.url);
            host.base.setBookState(book.url, on, on, on);
            BackgroundSync.setInterval(host, book.url, on ? minutes : 0);
        }
        BackgroundSync.reconcile(host, host.base.loadAllAddressbooks());

        // The permissions the chosen setup needs, asked up front in one
        // grouped request (two requestPermissions calls would cancel
        // each other): contacts because the subscribed books mirror
        // into the phone's Contacts app by default, sparing the ask at
        // the first phone-touching sync; notifications (Android 13+)
        // when a background cadence is on, for its sync report.
        List<String> permissions = new ArrayList<>();
        if (!host.hasContactsPermission()) {
            permissions.add(Manifest.permission.READ_CONTACTS);
            permissions.add(Manifest.permission.WRITE_CONTACTS);
        }
        if (minutes > 0
                && Build.VERSION.SDK_INT >= 33
                && host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            host.requestPermissions(
                    permissions.toArray(new String[0]), MainActivity.REQUEST_CONTACTS);
        }

        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
        host.syncRemote(true);
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }

    /** One addressbook's onboarding subscribe checkbox. */
    private static final class BookChoice {
        final String url;
        final CheckBox subscribe;

        BookChoice(String url, CheckBox subscribe) {
            this.url = url;
            this.subscribe = subscribe;
        }
    }
}
