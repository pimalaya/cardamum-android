package org.pimalaya.cardamum;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.cardamum.client.Account;

/**
 * Caches the single CardDAV account locally, encrypted with an AES-GCM key
 * held in the Android Keystore. The password never touches disk in clear,
 * and no Jetpack Security / Tink dependency is pulled in (smallest APK).
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

    /** The cached account, or null on first launch. */
    public Account load() {
        String payload = prefs.getString(KEY_PAYLOAD, null);
        String iv = prefs.getString(KEY_IV, null);
        if (payload == null || iv == null) {
            return null;
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec =
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP));
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec);

            byte[] plaintext = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP));
            return decode(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("Could not load the account", error);
        }
    }

    /** Encrypts and persists the account, replacing any previous one. */
    public void save(Account account) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());

            byte[] ciphertext =
                    cipher.doFinal(encode(account).getBytes(StandardCharsets.UTF_8));

            prefs.edit()
                    .putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException("Could not save the account", error);
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

    private static String encode(Account account) {
        try {
            return new JSONObject()
                    .put("baseUrl", account.baseUrl)
                    .put("login", account.login)
                    .put("password", account.password)
                    .toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode the account", error);
        }
    }

    private static Account decode(String json) {
        try {
            JSONObject object = new JSONObject(json);
            return new Account(
                    object.getString("baseUrl"),
                    object.getString("login"),
                    object.getString("password"));
        } catch (JSONException error) {
            throw new IllegalStateException("Could not decode the account", error);
        }
    }
}
