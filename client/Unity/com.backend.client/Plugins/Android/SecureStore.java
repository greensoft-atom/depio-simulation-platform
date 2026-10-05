package com.backend.client;

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

/**
 * The account's key on Android (docs 08 §8), for Runtime/SecureStores.cs's KeystoreStore: each value sealed with
 * AES-GCM by a key that lives in the AndroidKeyStore and never leaves it, the sealed bytes in this app's private
 * preferences. No library: the platform's own API, from Android 6 (API 23), the player settings' minimum.
 */
public final class SecureStore {

    private static final String ALIAS = "com.backend.client.key";
    private static final String PREFERENCES = "com.backend.client.secure";
    private static final int IV_BYTES = 12;

    private final SharedPreferences preferences;

    public SecureStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /**
     * The value, or null for none, or for one that can never open again: its key gone with a reset or a restore,
     * or its bytes not that key's. Anything else, the keystore failing for now, is thrown: read as none, the
     * account's key would be replaced by a new guest's and lost for good (client review, 2026-10-04).
     */
    public String get(String name) {
        String sealed = preferences.getString(name, null);
        if (sealed == null) {
            return null;
        }
        SecretKey key;
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias(ALIAS)) {
                return null;                                     // the key is gone: this can never open
            }
            key = ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        } catch (Exception e) {
            throw new IllegalStateException("the keystore cannot be read now", e);
        }
        try {
            byte[] all = Base64.decode(sealed, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException | IllegalArgumentException e) {
            return null;                                         // not this key's bytes: this can never open
        } catch (Exception e) {
            throw new IllegalStateException("the keystore would not open the value now", e);
        }
    }

    public void set(String name, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(sealed, 0, all, iv.length, sealed.length);
            preferences.edit().putString(name, Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("the keystore would not seal the value", e);
        }
    }

    public void delete(String name) {
        preferences.edit().remove(name).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
