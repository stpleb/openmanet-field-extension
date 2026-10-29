package com.manetnode.launcher;

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
 * The node password, saved once so every node signs in by itself. Encrypted with an Android Keystore
 * key (AES-GCM) that never leaves the phone's secure hardware; only this app can decrypt it.
 */
final class Secret {
    private static final String ALIAS = "manetnode-login", PREFS = "login";

    private Secret() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void save(Context ctx, String user, String pass) { save(prefs(ctx), user, pass); }

    /** {user, password} or null. */
    static String[] load(Context ctx) { return load(prefs(ctx)); }

    private static void save(SharedPreferences sp, String user, String pass) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] enc = c.doFinal(pass.getBytes(StandardCharsets.UTF_8));
            sp.edit().putString("user", user)
                    .putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
                    .putString("pw", Base64.encodeToString(enc, Base64.NO_WRAP)).apply();
        } catch (Exception ignored) {
            // no keystore: simply not remembered
        }
    }

    private static String[] load(SharedPreferences p) {
        String iv = p.getString("iv", null), pw = p.getString("pw", null);
        if (iv == null || pw == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String[]{p.getString("user", "root"), new String(c.doFinal(Base64.decode(pw, Base64.NO_WRAP)), StandardCharsets.UTF_8)};
        } catch (Exception e) {
            return null;
        }
    }

    static boolean saved(Context ctx) { return prefs(ctx).contains("pw"); }

    static void clear(Context ctx) { prefs(ctx).edit().clear().apply(); }
}
