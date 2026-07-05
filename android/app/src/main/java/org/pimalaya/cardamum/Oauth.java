package org.pimalaya.cardamum;

/**
 * OAuth 2.0 provider defaults for the connection flow.
 *
 * <p>These belong in pimconf eventually (so every Pimalaya app shares
 * one set of provider rules); they live here for now so the Google
 * CardDAV OAuth path can be tried end to end. The Google client is an
 * Android OAuth client: no secret (PKCE only), and a redirect on the
 * reversed-client-id custom scheme the OS routes back via the manifest
 * intent-filter.
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

    /** Google's CardDAV principal root; standard PROPFIND discovery runs from here. */
    static String googleCardDavBase(String email) {
        return "https://www.googleapis.com/carddav/v1/principals/" + email + "/";
    }

    private Oauth() {}
}
