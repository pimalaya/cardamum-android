package org.pimalaya.cardamum.client;

/** A usable CardDAV account: a discovered context root plus the entered password. */
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
