package org.pimalaya.cardamum;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;

/**
 * Caches the connected CardDAV accounts locally, encrypted with an
 * AES-GCM key held in the Android Keystore. Passwords and OAuth tokens
 * never touch disk in clear, and no Jetpack Security / Tink dependency
 * is pulled in (smallest APK). The whole account list is one encrypted
 * blob, keyed by the account email.
 */
public class SecureStore {
    private static final String PREFS = "cardamum.account";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_IV = "iv";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cardamum.account.key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every cached account, empty on first launch. */
    public List<AccountEntry> loadAll() {
        String payload = prefs.getString(KEY_PAYLOAD, null);
        String iv = prefs.getString(KEY_IV, null);
        if (payload == null || iv == null) {
            return new ArrayList<>();
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec =
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP));
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec);

            byte[] plaintext = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP));
            return decode(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Could not load the accounts", error);
        }
    }

    /** Adds an account (replacing any with the same email), then persists. */
    public void add(AccountEntry entry) {
        List<AccountEntry> entries = loadAll();
        entries.removeIf(candidate -> candidate.email.equals(entry.email));
        entries.add(entry);
        save(entries);
    }

    /** Removes the account with the given email, then persists. */
    public void remove(String email) {
        List<AccountEntry> entries = loadAll();
        entries.removeIf(entry -> entry.email.equals(email));
        save(entries);
    }

    private void save(List<AccountEntry> entries) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());

            byte[] ciphertext = cipher.doFinal(encode(entries).getBytes(StandardCharsets.UTF_8));

            prefs.edit()
                    .putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException("Could not save the accounts", error);
        }
    }

    /** Returns the Keystore AES key, generating it on first use. */
    private SecretKey secretKey() throws Exception {
        KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keystore.load(null);

        KeyStore.Entry entry = keystore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }

    private static String encode(List<AccountEntry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (AccountEntry entry : entries) {
                JSONObject object =
                        new JSONObject()
                                .put("baseUrl", entry.account.baseUrl)
                                .put("login", entry.account.login)
                                .put("password", entry.account.password)
                                .put("email", entry.email);
                if (entry.refreshToken != null) {
                    object.put("refreshToken", entry.refreshToken)
                            .put("tokenEndpoint", entry.tokenEndpoint)
                            .put("clientId", entry.clientId);
                    if (entry.clientSecret != null) {
                        object.put("clientSecret", entry.clientSecret);
                    }
                }
                array.put(object);
            }
            return array.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode the accounts", error);
        }
    }

    private static List<AccountEntry> decode(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<AccountEntry> entries = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                Account account =
                        new Account(
                                object.getString("baseUrl"),
                                object.getString("login"),
                                object.getString("password"));
                entries.add(
                        new AccountEntry(
                                account,
                                object.getString("email"),
                                object.isNull("refreshToken")
                                        ? null
                                        : object.optString("refreshToken"),
                                object.isNull("tokenEndpoint")
                                        ? null
                                        : object.optString("tokenEndpoint"),
                                object.isNull("clientId") ? null : object.optString("clientId"),
                                object.isNull("clientSecret")
                                        ? null
                                        : object.optString("clientSecret")));
            }
            return entries;
        } catch (JSONException error) {
            throw new IllegalStateException("Could not decode the accounts", error);
        }
    }
}
