package org.pimalaya.cardamum;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.InetAddress;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;
import org.pimalaya.cardamum.client.OauthSession;
import org.pimalaya.cardamum.client.OauthTokens;
import org.pimalaya.cardamum.client.ServerMetadata;

/**
 * The connection flow's OAuth 2.0 grants: the shipped Google and
 * Microsoft clients, custom clients over a loopback or the app-scheme
 * redirect, and the zero-registration issuer path (RFC 8414 metadata
 * plus RFC 7591 dynamic registration). Every grant ends in the browser;
 * the OS-routed redirects come back through the host's onNewIntent into
 * {@link #handleRedirect}, and the redeemed tokens land back in the
 * connection flow through {@link #onConnected}. The in-flight grant
 * persists to preferences because the browser hop routinely outlives
 * the process.
 */
final class OauthFlow {
    /** Lands a redeemed grant back in the connection flow. */
    interface Connector {
        void connect(
                Account account,
                String email,
                String refreshToken,
                String tokenEndpoint,
                String clientId,
                String clientSecret);
    }

    /** Name of the preferences holding the in-flight OAuth grant. */
    private static final String OAUTH_PREFS = "cardamum.oauth";

    private final MainActivity host;

    /** Wired by the host after construction (the two flows reference
     *  each other, so one side binds late). */
    Connector onConnected;

    /** Resets the config screen's continue when a grant aborts. */
    Runnable onAborted;

    /** The in-flight grant: the PKCE session and the account material
     *  the redeem needs (all null or empty when no grant runs). */
    private OauthSession pendingOauth;

    private String pendingTokenEndpoint;
    private String pendingBaseUrl;
    private String pendingAccountEmail;
    private String pendingClientId;
    private String pendingClientSecret;

    /** The loopback redirect listener, closed with the activity. */
    private java.net.ServerSocket loopbackServer;

    OauthFlow(MainActivity host) {
        this.host = host;
    }

    /**
     * Starts a Google OAuth grant (the scope picks the backend: CardDAV
     * against the given principal root, or the People API against a
     * google sentinel base URL): prepares the PKCE session, then opens
     * the authorization URL in the browser. The redirect comes back on
     * the reversed-client-id custom scheme.
     */
    void startGoogleOauth(String email, String scope, String baseUrl) {
        pendingOauth =
                new OauthSession(Oauth.GOOGLE_CLIENT_ID, null, Oauth.GOOGLE_REDIRECT_URI, scope);
        pendingTokenEndpoint = Oauth.GOOGLE_TOKEN_ENDPOINT;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = email;
        pendingClientId = Oauth.GOOGLE_CLIENT_ID;
        pendingClientSecret = null;

        // NOTE: access_type=offline + prompt=consent make Google issue a
        // refresh token; login_hint preselects the entered account.
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.GOOGLE_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.GOOGLE_CLIENT_ID, Oauth.GOOGLE_REDIRECT_URI);
            host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Starts the Microsoft Graph OAuth grant, mirroring the Google one:
     * PKCE session, browser, redirect on the cardamum custom scheme.
     * The connected account carries the msgraph sentinel base URL,
     * which routes every addressbook and card operation through Graph
     * instead of CardDAV.
     */
    void startMicrosoftOauth(String email) {
        pendingOauth =
                new OauthSession(
                        Oauth.MICROSOFT_CLIENT_ID,
                        null,
                        Oauth.MICROSOFT_REDIRECT_URI,
                        Oauth.MICROSOFT_SCOPE);
        pendingTokenEndpoint = Oauth.MICROSOFT_TOKEN_ENDPOINT;
        pendingBaseUrl = org.pimalaya.cardamum.client.CardamumClient.msgraphBase(email);
        pendingAccountEmail = email;
        pendingClientId = Oauth.MICROSOFT_CLIENT_ID;
        pendingClientSecret = null;

        // NOTE: the refresh token comes from the offline_access scope,
        // no extra parameter needed; login_hint preselects the account.
        JSONObject extras = new JSONObject();
        try {
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.MICROSOFT_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.MICROSOFT_CLIENT_ID, Oauth.MICROSOFT_REDIRECT_URI);
            host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Redeems the OAuth redirect for tokens and connects with them, the
     * access token standing in for the password (empty login, Bearer
     * auth). No-op unless a grant is in flight for this redirect,
     * restoring a persisted one when the process died while the user
     * was in the browser.
     */
    void handleRedirect(Intent intent) {
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

        // NOTE: the one-time code is redacted; everything else in the
        // redirect is needed verbatim to debug rejections.
        Log.d(
                "cardamum",
                "oauth redirect: " + redirectUrl.replaceAll("code=[^&]*", "code=<redacted>"));

        host.io.execute(
                () -> {
                    try {
                        OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl);
                        host.main.post(
                                () ->
                                        onConnected.connect(
                                                new Account(baseUrl, "", tokens.accessToken),
                                                email,
                                                tokens.refreshToken,
                                                tokenEndpoint,
                                                clientId,
                                                clientSecret));
                    } catch (Exception error) {
                        Log.w("cardamum", "oauth redeem failed", error);
                        host.main.post(
                                () -> {
                                    onAborted.run();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /**
     * Prompts for the OAuth 2.0 client of a grant, every field
     * prefilled with the best-known values; a hint paragraph tells
     * users to leave them alone unless they bring their own client.
     * Submitting a shipped client id unchanged runs its dedicated flow.
     * Any other client runs the custom grant against the given redirect
     * URI: an http loopback URL serves the redirect on a local
     * listener, and the app's own org.pimalaya.cardamum:/oauth2redirect
     * scheme rides the OS intent route instead (for servers that reject
     * loopback redirects).
     */
    void promptOauthClient(
            String email,
            String baseUrl,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String defaultClientId,
            String defaultRedirect,
            Runnable defaultFlow) {
        String prefilledRedirect =
                defaultRedirect != null ? defaultRedirect : "http://127.0.0.1:" + freePort();

        EditText clientId =
                host.ui.field(
                        R.string.oauth_client_id, defaultClientId == null ? "" : defaultClientId);
        EditText clientSecret = host.ui.field(R.string.oauth_client_secret, "");
        EditText authField = host.ui.field(R.string.oauth_authorization_endpoint, authEndpoint);
        EditText tokenField = host.ui.field(R.string.oauth_token_endpoint, tokenEndpoint);
        EditText scopeField = host.ui.field(R.string.oauth_scope, scope == null ? "" : scope);
        EditText redirectField = host.ui.field(R.string.oauth_redirect, prefilledRedirect);

        LinearLayout fields = new LinearLayout(host);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        for (EditText field : new EditText[] {
            clientId, clientSecret, authField, tokenField, scopeField, redirectField
        }) {
            fields.addView(field);
        }

        boolean shipped = defaultFlow != null;

        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        if (shipped) {
            // NOTE: the paragraph rides in the content view (not
            // setMessage), so it, the checkbox and the fields share the
            // same 24dp inset instead of the dialog's message padding.
            TextView hint = new TextView(host);
            hint.setText(R.string.oauth_shipped_hint);
            hint.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
            hint.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), host.ui.dp(8));
            content.addView(hint);

            CheckBox advanced = new CheckBox(host);
            advanced.setText(R.string.oauth_advanced);
            // NOTE: a CheckBox's box ignores its own left padding, so the
            // 24dp indent must come from a start margin instead.
            advanced.setPadding(0, host.ui.dp(8), host.ui.dp(24), host.ui.dp(8));
            LinearLayout.LayoutParams advancedParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            advancedParams.setMarginStart(host.ui.dp(24));
            fields.setVisibility(View.GONE);
            advanced.setOnCheckedChangeListener(
                    (view, checked) ->
                            fields.setVisibility(checked ? View.VISIBLE : View.GONE));
            content.addView(advanced, advancedParams);
        }
        content.addView(fields);

        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(host).setTitle(R.string.oauth_custom_title);

        builder.setView(scroll)
                .setPositiveButton(
                        R.string.email_submit,
                        (dialog, which) -> {
                            String id = clientId.getText().toString().trim();
                            if (id.isEmpty()) {
                                host.toast(host.getString(R.string.oauth_client_id_empty));
                                return;
                            }
                            if (defaultFlow != null && id.equals(defaultClientId)) {
                                defaultFlow.run();
                                return;
                            }

                            String secret = clientSecret.getText().toString().trim();
                            String auth = authField.getText().toString().trim();
                            String token = tokenField.getText().toString().trim();
                            String scopes = scopeField.getText().toString().trim();
                            String redirect = redirectField.getText().toString().trim();

                            if (redirect.startsWith("http://")) {
                                startLoopbackOauth(
                                        email, id, secret, auth, token, scopes, baseUrl, redirect);
                            } else if (redirect.startsWith("org.pimalaya.cardamum:")) {
                                launchSchemeGrant(
                                        email,
                                        baseUrl,
                                        id,
                                        secret.isEmpty() ? null : secret,
                                        auth,
                                        token,
                                        scopes,
                                        null);
                            } else {
                                host.toast(host.getString(R.string.oauth_redirect_invalid));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Runs the zero-configuration OAuth path from a bare issuer: fetch
     * its RFC 8414 metadata, register a public client dynamically
     * (RFC 7591) when the server allows it, then drive the ordinary
     * code grant with the freshly issued client id over the OS-routed
     * custom-scheme redirect (the servers that support registration,
     * fastmail and Stalwart, reject a loopback http redirect but accept
     * the private-use scheme, RFC 8252 §7.1). When the server publishes
     * no registration endpoint, falls back to the custom-client prompt
     * with the endpoints prefilled, so the user can paste a
     * self-registered client id instead.
     */
    void startIssuerOauth(String email, String baseUrl, String issuer, String resource) {
        host.setAuthLoading(R.id.fab, R.id.fab_progress, true);

        host.io.execute(
                () -> {
                    try {
                        ServerMetadata metadata = host.client.oauthServerMetadata(issuer);
                        if (metadata.authorizationEndpoint == null
                                || metadata.tokenEndpoint == null) {
                            throw new IllegalStateException(
                                    host.getString(R.string.oauth_metadata_incomplete));
                        }

                        if (!metadata.supportsDynamicRegistration()) {
                            // No dynamic registration: let the user bring
                            // a self-registered client id, endpoints
                            // prefilled.
                            host.main.post(
                                    () -> {
                                        onAborted.run();
                                        promptOauthClient(
                                                email,
                                                baseUrl,
                                                metadata.authorizationEndpoint,
                                                metadata.tokenEndpoint,
                                                metadata.scopesSupported,
                                                null,
                                                null,
                                                null);
                                    });
                            return;
                        }

                        // NOTE: request only the contacts scope (plus
                        // offline_access), not the server's whole set:
                        // asking for mail/calendar extras earns an
                        // invalid_scope authorization error.
                        String scope = metadata.contactsScope();
                        String clientId =
                                host.client.oauthRegisterClient(
                                        metadata.registrationEndpoint,
                                        Oauth.REDIRECT_URI,
                                        host.getString(R.string.app_name),
                                        scope);
                        Log.d(
                                "cardamum",
                                "issuer grant: client "
                                        + clientId
                                        + ", scope \""
                                        + scope
                                        + "\", authorize "
                                        + metadata.authorizationEndpoint
                                        + ", token "
                                        + metadata.tokenEndpoint);

                        host.main.post(
                                () ->
                                        launchSchemeGrant(
                                                email,
                                                baseUrl,
                                                clientId,
                                                null,
                                                metadata.authorizationEndpoint,
                                                metadata.tokenEndpoint,
                                                scope,
                                                resource));
                    } catch (Exception error) {
                        Log.w("cardamum", "issuer oauth failed", error);
                        host.main.post(
                                () -> {
                                    onAborted.run();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /** Closes the loopback listener, if one is open. */
    void closeLoopback() {
        java.net.ServerSocket server = loopbackServer;
        loopbackServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * Opens the browser for a grant riding the app's own
     * org.pimalaya.cardamum:/oauth2redirect scheme: PKCE session over
     * the OS-routed redirect, persisted so it survives the process,
     * redeemed in {@link #handleRedirect}. Used by the
     * zero-registration issuer flow (no secret) and by custom clients
     * whose redirect is the app scheme (for servers that reject a
     * loopback redirect).
     */
    private void launchSchemeGrant(
            String email,
            String baseUrl,
            String clientId,
            String clientSecret,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String resource) {
        pendingOauth = new OauthSession(clientId, clientSecret, Oauth.REDIRECT_URI, scope);
        pendingTokenEndpoint = tokenEndpoint;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = email;
        pendingClientId = clientId;
        pendingClientSecret = clientSecret;

        // NOTE: the RFC 8707 resource is required: fastmail bounces a
        // request without one as invalid_target. Google's
        // access_type/prompt hints stay off this flow: refresh comes
        // from offline_access, and unknown parameters risk an
        // invalid_request bounce from strict servers.
        JSONObject extras = new JSONObject();
        try {
            if (resource != null && !resource.isEmpty()) {
                extras.put("resource", resource);
            }
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(authEndpoint, extras);
            Log.d("cardamum", "scheme grant authorize url: " + url);
            persistPendingOauth(clientId, Oauth.REDIRECT_URI);
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Runs a custom-client grant against an http loopback redirect URI:
     * binds its port, opens the browser, and serves the single redirect
     * the browser lands on, reconstructing its URL to redeem for
     * tokens. The listener lives on its own thread until the redirect
     * arrives or the wait times out; it dies with the process (a custom
     * grant is not persisted, having no OS-routed redirect to resume
     * through).
     */
    private void startLoopbackOauth(
            String email,
            String clientId,
            String clientSecret,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String baseUrl,
            String redirectUri) {
        String secret = clientSecret.isEmpty() ? null : clientSecret;

        Uri redirect = Uri.parse(redirectUri);
        String redirectHost = redirect.getHost();
        if (redirectHost == null
                || !(redirectHost.equals("127.0.0.1") || redirectHost.equals("localhost"))) {
            host.toast(host.getString(R.string.oauth_redirect_invalid));
            return;
        }
        int port = redirect.getPort() == -1 ? 80 : redirect.getPort();

        // NOTE: origin the browser's request target is appended to when
        // rebuilding the redirect URL; the typed URI may carry a path.
        String origin = "http://" + redirectHost + (redirect.getPort() == -1 ? "" : ":" + port);

        java.net.ServerSocket server;
        try {
            server = new java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(300_000);
        } catch (Exception error) {
            host.showError(error, R.string.oauth_port_taken);
            return;
        }
        loopbackServer = server;

        OauthSession session = new OauthSession(clientId, secret, redirectUri, scope);

        // NOTE: access_type=offline + prompt=consent make Google-style
        // servers issue a refresh token (ignored by servers that do not
        // use them); login_hint preselects the account.
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        host.io.execute(
                () -> {
                    try {
                        String authUrl = session.authorizeUrl(authEndpoint, extras);
                        host.main.post(
                                () -> {
                                    host.setAuthLoading(R.id.fab, R.id.fab_progress, true);
                                    host.startActivity(
                                            new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)));
                                });

                        String redirectUrl = awaitLoopbackRedirect(server, origin);
                        OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl);
                        host.main.post(
                                () ->
                                        onConnected.connect(
                                                new Account(baseUrl, "", tokens.accessToken),
                                                email,
                                                tokens.refreshToken,
                                                tokenEndpoint,
                                                clientId,
                                                secret));
                    } catch (Exception error) {
                        Log.w("cardamum", "custom oauth failed", error);
                        host.main.post(
                                () -> {
                                    onAborted.run();
                                    host.showError(error, R.string.connect_failed);
                                });
                    } finally {
                        closeLoopback();
                    }
                });
    }

    /**
     * Accepts the one browser redirect on the loopback socket, answers
     * a close-this-tab page, and rebuilds the redirect URL from the
     * given origin and the request target so it can be validated and
     * redeemed.
     */
    private String awaitLoopbackRedirect(java.net.ServerSocket server, String origin)
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
                            + host.getString(R.string.oauth_loopback_done)
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

            // NOTE: "GET /?code=..&state=.. HTTP/1.1" -> parts[1] is the
            // request target (the path and query the browser hit).
            String target = "/";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    target = parts[1];
                }
            }
            return origin + target;
        }
    }

    /**
     * Persists the in-flight grant: the browser hop routinely outlives
     * the process (the system reclaims backgrounded apps), and without
     * the state and PKCE verifier the redirect could not be redeemed.
     */
    private void persistPendingOauth(String clientId, String redirectUri) {
        host.getSharedPreferences(OAUTH_PREFS, android.content.Context.MODE_PRIVATE)
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
        android.content.SharedPreferences prefs =
                host.getSharedPreferences(OAUTH_PREFS, android.content.Context.MODE_PRIVATE);
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
        host.getSharedPreferences(OAUTH_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    /** A grant that never reached the browser: drop it and report. */
    private void abort(Exception error) {
        pendingOauth = null;
        clearPendingOauth();
        onAborted.run();
        host.showError(error, R.string.connect_failed);
    }

    /** A free loopback TCP port, for the redirect prefill. */
    private static int freePort() {
        try (java.net.ServerSocket probe =
                new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        } catch (Exception error) {
            // NOTE: fallback in the ephemeral range; the bind at grant
            // time surfaces a clash as an error.
            return 8117;
        }
    }
}
