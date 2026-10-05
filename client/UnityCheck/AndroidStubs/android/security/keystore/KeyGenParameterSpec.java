// A stub of the Android API Plugins/Android/SecureStore.java uses, to its signatures, for compiling only (D-73).
package android.security.keystore;

import java.security.spec.AlgorithmParameterSpec;

public final class KeyGenParameterSpec implements AlgorithmParameterSpec {
    public static final class Builder {
        public Builder(String keystoreAlias, int purposes) {
            throw new UnsupportedOperationException("a stub");
        }

        public Builder setBlockModes(String... blockModes) {
            throw new UnsupportedOperationException("a stub");
        }

        public Builder setEncryptionPaddings(String... paddings) {
            throw new UnsupportedOperationException("a stub");
        }

        public KeyGenParameterSpec build() {
            throw new UnsupportedOperationException("a stub");
        }
    }
}
