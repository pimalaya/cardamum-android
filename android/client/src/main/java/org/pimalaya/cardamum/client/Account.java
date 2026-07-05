package org.pimalaya.cardamum.client;

/**
 * A usable CardDAV account: a discovered context root plus the entered
 * credentials. An empty login means the password field carries an
 * OAuth 2.0 access token, authenticated as Bearer instead of Basic.
 */
public final class Account {
    public final String baseUrl;
    public final String login;
    public final String password;

    public Account(String baseUrl, String login, String password) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.password = password;
    }
}
