package org.pimalaya.cardamum;

import org.pimalaya.cardamum.client.Account;

/**
 * A stored account plus its display email, which is the account's name
 * (the home screen groups addressbooks by it). An OAuth account carries
 * an empty {@link Account#login}, so the email cannot be recovered from
 * the credentials and is kept alongside.
 */
final class AccountEntry {
    final Account account;
    final String email;

    /** Refresh token of an OAuth account; null for password accounts. */
    final String refreshToken;

    /** Token endpoint the refresh runs against; null without a refresh token. */
    final String tokenEndpoint;

    /** OAuth client the tokens were issued to; null without a refresh token. */
    final String clientId;

    /**
     * Secret of the OAuth client, when its registration issued one
     * (Google desktop-type clients require it in every exchange); null
     * for secret-less clients and password accounts.
     */
    final String clientSecret;

    AccountEntry(Account account, String email) {
        this(account, email, null, null, null, null);
    }

    AccountEntry(
            Account account,
            String email,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        this.account = account;
        this.email = email;
        this.refreshToken = refreshToken;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }
}
