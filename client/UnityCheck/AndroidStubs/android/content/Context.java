// A stub of the Android API Plugins/Android/SecureStore.java uses, to its signatures, for compiling only (D-73).
package android.content;

public abstract class Context {
    public static final int MODE_PRIVATE = 0;

    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
