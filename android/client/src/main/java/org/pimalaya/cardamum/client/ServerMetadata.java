package org.pimalaya.cardamum.client;

import org.json.JSONObject;

/**
 * The subset of an authorization server's RFC 8414 metadata the
 * onboarding needs: the endpoints to drive the code grant, and the
 * RFC 7591 registration endpoint when the server lets a public client
 * register itself (null otherwise, meaning a pre-registered client id
 * is required).
 */
public final class ServerMetadata {
    public final String issuer;
    public final String authorizationEndpoint;
    public final String tokenEndpoint;

    /** RFC 7591 registration endpoint, or null when unsupported. */
    public final String registrationEndpoint;

    /** Space-separated supported scopes, empty when unadvertised. */
    public final String scopesSupported;

    ServerMetadata(JSONObject json) {
        this.issuer = json.optString("issuer", null);
        this.authorizationEndpoint = json.optString("authorization_endpoint", null);
        this.tokenEndpoint = json.optString("token_endpoint", null);
        this.registrationEndpoint =
                json.isNull("registration_endpoint")
                        ? null
                        : json.optString("registration_endpoint", null);

        StringBuilder scopes = new StringBuilder();
        org.json.JSONArray supported = json.optJSONArray("scopes_supported");
        for (int index = 0; supported != null && index < supported.length(); index++) {
            if (scopes.length() > 0) {
                scopes.append(' ');
            }
            scopes.append(supported.optString(index));
        }
        this.scopesSupported = scopes.toString();
    }

    /** Whether a public client can register itself here (RFC 7591). */
    public boolean supportsDynamicRegistration() {
        return registrationEndpoint != null && !registrationEndpoint.isEmpty();
    }

    /**
     * The scope a contacts client should request here: the standard
     * contacts scope (draft-ietf-mailmaint-oauth-public) plus
     * {@code offline_access} for the refresh token, each kept only
     * when the server advertised it. Requesting the server's whole
     * {@link #scopesSupported} set instead is what an "invalid scope"
     * authorization error comes from: that set is what the server
     * supports (mail, calendars, provider extras), not what this app
     * needs. When the server advertised no scopes at all, the standard
     * pair is requested as-is.
     */
    public String contactsScope() {
        String contacts = "urn:ietf:params:oauth:scope:contacts";
        String offline = "offline_access";

        if (scopesSupported.isEmpty()) {
            return contacts + " " + offline;
        }

        StringBuilder scope = new StringBuilder();
        for (String wanted : new String[] {contacts, offline}) {
            if ((" " + scopesSupported + " ").contains(" " + wanted + " ")) {
                if (scope.length() > 0) {
                    scope.append(' ');
                }
                scope.append(wanted);
            }
        }
        return scope.toString();
    }
}
