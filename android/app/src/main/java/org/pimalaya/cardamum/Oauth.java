package org.pimalaya.cardamum;

/**
 * OAuth 2.0 provider defaults for the connection flow: the app's
 * client registrations, their redirect URIs (mirrored by the manifest
 * intent-filters) and the provider endpoints and scopes. The account
 * base URLs derived from these choices come from the bridge (the
 * {@code *Base} helpers on
 * {@link org.pimalaya.cardamum.client.CardamumClient}).
 *
 * <p>The endpoints and scopes belong in pimconf eventually (so every
 * Pimalaya app shares one set of provider rules). The Google client is
 * an Android OAuth client: no secret (PKCE only), and a redirect on
 * the reversed-client-id custom scheme the OS routes back via the
 * manifest intent-filter.
 */
final class Oauth {
    static final String GOOGLE_CLIENT_ID =
            "23028300025-v4ku58bh4himktfvu8l4u5dmfhoqfdqp.apps.googleusercontent.com";

    /** Reversed-client-id scheme, mirrored by the manifest intent-filter. */
    static final String GOOGLE_REDIRECT_URI =
            "com.googleusercontent.apps.23028300025-v4ku58bh4himktfvu8l4u5dmfhoqfdqp:/oauth2redirect";

    static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** CardDAV scope (the one DAVx5 uses); reuses the WebDAV transport. */
    static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/carddav";

    /** People API scope, for the Google Contacts API backend. */
    static final String GOOGLE_PEOPLE_SCOPE = "https://www.googleapis.com/auth/contacts";

    /**
     * The Pimalaya Entra app registration (a public client: no secret,
     * PKCE only, like the Google one).
     */
    static final String MICROSOFT_CLIENT_ID = "d535213d-eead-44ce-9564-eecddc194428";

    /** Custom scheme, mirrored by the manifest intent-filter. */
    static final String MICROSOFT_REDIRECT_URI = "cardamum://oauth2redirect";

    /**
     * The redirect of the dynamic-registration issuer flow: a
     * reverse-DNS private-use scheme (RFC 8252 §7.1), mirrored by the
     * manifest intent-filter. Fastmail's registration rejects a
     * loopback http redirect and a bare (dot-less) scheme alike, and
     * accepts this reverse-DNS form; Stalwart accepts it too. Single
     * slash per RFC 8252 §7.1: a private-use scheme has no naming
     * authority, so no {@code //} component.
     */
    static final String REDIRECT_URI = "org.pimalaya.cardamum:/oauth2redirect";

    /** The `common` tenant serves both personal (MSA) and Entra accounts. */
    static final String MICROSOFT_AUTH_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";

    static final String MICROSOFT_TOKEN_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    /** Graph contacts scope; offline_access makes Entra issue a refresh token. */
    static final String MICROSOFT_SCOPE =
            "https://graph.microsoft.com/Contacts.ReadWrite offline_access";

    private Oauth() {}
}
