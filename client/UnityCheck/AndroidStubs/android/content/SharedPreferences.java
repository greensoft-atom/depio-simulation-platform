// A stub of the Android API Plugins/Android/SecureStore.java uses, to its signatures, for compiling only (D-73).
package android.content;

public interface SharedPreferences {
    String getString(String key, String defValue);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor remove(String key);

        void apply();
    }
}
