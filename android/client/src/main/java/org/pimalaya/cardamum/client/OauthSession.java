package org.pimalaya.cardamum.client;

import java.security.SecureRandom;
import org.json.JSONObject;

/**
 * One OAuth 2.0 authorization code grant with PKCE: generates the CSRF
 * state and PKCE verifier, builds the authorization URL to open in the
 * user's browser, and exchanges the redirect for tokens. Keep the
 * instance alive between {@link #authorizeUrl} and {@link #redeem} so
 * the state and verifier match. {@link #redeem} blocks, so callers
 * must run it off the main thread.
 */
public final class OauthSession {
    // RFC 7636 unreserved set, valid for both the PKCE verifier and
    // the RFC 6749 VSCHAR state.
    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private final String clientId;
    private final String redirectUri;
    private final String scope;
    private final String state;
    private final String pkceVerifier;

    /**
     * Prepares a grant for the app's OAuth client. The redirect URI is
     * the app link or custom scheme the app intercepts; the scope
     * usually comes from the matching {@link AuthMethod#scope}.
     */
    public OauthSession(String clientId, String redirectUri, String scope) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope == null ? "" : scope;
        this.state = random(32);
        this.pkceVerifier = random(64);
    }

    /**
     * Builds the authorization URL to open in the user's browser.
     * {@code extras} carries provider-specific query parameters
     * (nullable), e.g. Google's {@code access_type=offline} and
     * {@code prompt=consent} without which no refresh token is issued.
     */
    public String authorizeUrl(String authorizationEndpoint, JSONObject extras) {
        JSONObject reply =
                CardamumClient.object(
                        Native.oauthAuthorizeUrl(
                                authorizationEndpoint,
                                clientId,
                                redirectUri,
                                scope,
                                state,
                                pkceVerifier,
                                extras == null ? "" : extras.toString()));
        return CardamumClient.string(reply, "url");
    }

    /**
     * Validates the intercepted redirect URL (CSRF state check) and
     * exchanges its authorization code for tokens.
     */
    public OauthTokens redeem(String tokenEndpoint, String redirectUrl) {
        JSONObject validated =
                CardamumClient.object(Native.oauthValidateRedirect(redirectUrl, state));
        String code = CardamumClient.string(validated, "code");

        Transport transport = new Transport();
        try {
            JSONObject reply =
                    CardamumClient.object(
                            Native.oauthRequestAccessToken(
                                    transport,
                                    tokenEndpoint,
                                    clientId,
                                    code,
                                    redirectUri,
                                    pkceVerifier));
            return OauthTokens.from(reply);
        } finally {
            transport.close();
        }
    }

    private static String random(int size) {
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(size);

        for (int index = 0; index < size; index++) {
            value.append(UNRESERVED.charAt(random.nextInt(UNRESERVED.length())));
        }

        return value.toString();
    }
}
