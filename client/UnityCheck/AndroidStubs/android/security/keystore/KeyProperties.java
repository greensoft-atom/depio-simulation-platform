// A stub of the Android API Plugins/Android/SecureStore.java uses, to its signatures, for compiling only (D-73).
package android.security.keystore;

public abstract class KeyProperties {
    public static final int PURPOSE_ENCRYPT = 1;
    public static final int PURPOSE_DECRYPT = 2;
    public static final String KEY_ALGORITHM_AES = "AES";
    public static final String BLOCK_MODE_GCM = "GCM";
    public static final String ENCRYPTION_PADDING_NONE = "NoPadding";
}
