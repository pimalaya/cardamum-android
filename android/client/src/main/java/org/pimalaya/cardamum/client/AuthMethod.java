package org.pimalaya.cardamum.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One way a {@link ServiceConfig} accepts authentication. Only the
 * fields relevant to the {@link #type} are non-null.
 */
public final class AuthMethod {
    public enum Type {
        /** Username and password login (possibly an app password). */
        PASSWORD,
        /** OAuth 2.0 authorization code grant (RFC 6749). */
        OAUTH_AUTHORIZATION_CODE_GRANT,
        /** OAuth 2.0 device authorization grant (RFC 8628). */
        OAUTH_DEVICE_AUTHORIZATION_GRANT,
        /** OAuth 2.0 with grants to resolve from the issuer's RFC 8414 metadata. */
        OAUTH_ISSUER,
    }

    public final Type type;

    /** Authorization endpoint (code grant only). */
    public final String authorizationEndpoint;

    /** Device authorization endpoint (device grant only). */
    public final String deviceAuthorizationEndpoint;

    /** Token endpoint (both OAuth grants). */
    public final String tokenEndpoint;

    /** Space-separated scopes to request (both OAuth grants). */
    public final String scope;

    /** Issuer URL ({@link Type#OAUTH_ISSUER} only). */
    public final String issuer;

    private AuthMethod(
            Type type,
            String authorizationEndpoint,
            String deviceAuthorizationEndpoint,
            String tokenEndpoint,
            String scope,
            String issuer) {
        this.type = type;
        this.authorizationEndpoint = authorizationEndpoint;
        this.deviceAuthorizationEndpoint = deviceAuthorizationEndpoint;
        this.tokenEndpoint = tokenEndpoint;
        this.scope = scope;
        this.issuer = issuer;
    }

    /**
     * Parses one method from the bridge's JSON shape: {@code "password"}
     * as a plain string, the OAuth variants as single-key objects.
     */
    static AuthMethod from(Object json) {
        if (!(json instanceof JSONObject)) {
            return new AuthMethod(Type.PASSWORD, null, null, null, null, null);
        }

        JSONObject method = (JSONObject) json;
        try {
            if (method.has("oauthAuthorizationCodeGrant")) {
                JSONObject grant = method.getJSONObject("oauthAuthorizationCodeGrant");
                return new AuthMethod(
                        Type.OAUTH_AUTHORIZATION_CODE_GRANT,
                        grant.getString("authorization_endpoint"),
                        null,
                        grant.getString("token_endpoint"),
                        grant.isNull("scope") ? null : grant.getString("scope"),
                        null);
            }

            if (method.has("oauthDeviceAuthorizationGrant")) {
                JSONObject grant = method.getJSONObject("oauthDeviceAuthorizationGrant");
                return new AuthMethod(
                        Type.OAUTH_DEVICE_AUTHORIZATION_GRANT,
                        null,
                        grant.getString("device_authorization_endpoint"),
                        grant.getString("token_endpoint"),
                        grant.isNull("scope") ? null : grant.getString("scope"),
                        null);
            }

            if (method.has("oauthIssuer")) {
                return new AuthMethod(
                        Type.OAUTH_ISSUER, null, null, null, null, method.getString("oauthIssuer"));
            }
        } catch (JSONException error) {
            throw new CardamumException("Unreadable auth method: " + error.getMessage());
        }

        throw new CardamumException("Unreadable auth method: " + method);
    }
}
