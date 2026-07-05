package org.pimalaya.cardamum.client;

import org.json.JSONObject;

/**
 * Tokens returned by an OAuth 2.0 code exchange or refresh. To use the
 * access token against CardDAV, build the {@link Account} with an
 * empty login and the token as password: the bridge then authenticates
 * with Bearer instead of Basic.
 */
public final class OauthTokens {
    public final String accessToken;

    /** Token type, {@code Bearer} in practice. */
    public final String tokenType;

    /** Lifetime in seconds; 0 when the server omitted it. */
    public final long expiresIn;

    /** Refresh token; null when the server issued none. */
    public final String refreshToken;

    /** Granted scopes; null when identical to the requested ones. */
    public final String scope;

    /** Unix epoch seconds when issued (server clock); 0 when unknown. */
    public final long issuedAt;

    private OauthTokens(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            String scope,
            long issuedAt) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
        this.refreshToken = refreshToken;
        this.scope = scope;
        this.issuedAt = issuedAt;
    }

    /** Parses the bridge's RFC 6749 §5.1 success params JSON. */
    static OauthTokens from(JSONObject json) {
        return new OauthTokens(
                json.optString("access_token"),
                json.optString("token_type", "Bearer"),
                json.optLong("expires_in", 0),
                json.isNull("refresh_token") ? null : json.optString("refresh_token"),
                json.isNull("scope") ? null : json.optString("scope"),
                json.optLong("issued_at", 0));
    }
}
