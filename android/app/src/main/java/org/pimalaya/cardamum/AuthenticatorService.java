package org.pimalaya.cardamum;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/**
 * Stub authenticator for Cardamum's account type. Accounts are created
 * by the app itself after the addressbook selection, never through the
 * system's "add account" flow, so every callback answers empty.
 */
public class AuthenticatorService extends Service {
    private Authenticator authenticator;

    @Override
    public void onCreate() {
        authenticator = new Authenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }

    private static final class Authenticator extends AbstractAccountAuthenticator {
        Authenticator(Context context) {
            super(context);
        }

        @Override
        public Bundle editProperties(AccountAuthenticatorResponse response, String accountType) {
            return null;
        }

        @Override
        public Bundle addAccount(
                AccountAuthenticatorResponse response,
                String accountType,
                String authTokenType,
                String[] requiredFeatures,
                Bundle options) {
            return null;
        }

        @Override
        public Bundle confirmCredentials(
                AccountAuthenticatorResponse response, Account account, Bundle options) {
            return null;
        }

        @Override
        public Bundle getAuthToken(
                AccountAuthenticatorResponse response,
                Account account,
                String authTokenType,
                Bundle options) {
            return null;
        }

        @Override
        public String getAuthTokenLabel(String authTokenType) {
            return null;
        }

        @Override
        public Bundle updateCredentials(
                AccountAuthenticatorResponse response,
                Account account,
                String authTokenType,
                Bundle options) {
            return null;
        }

        @Override
        public Bundle hasFeatures(
                AccountAuthenticatorResponse response, Account account, String[] features) {
            Bundle result = new Bundle();
            result.putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false);
            return result;
        }
    }
}
