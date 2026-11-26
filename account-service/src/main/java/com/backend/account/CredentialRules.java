package com.backend.account;

import java.util.Locale;

/** What a username and a password must be (10 §3.2): the platform's rules, moved (04 §1). */
public final class CredentialRules {

    static final int USERNAME_MIN = 3;
    static final int USERNAME_MAX = 32;
    static final int PASSWORD_MIN = 8;
    /** Argon2 does not care, but an unbounded password is an unbounded request body. */
    static final int PASSWORD_MAX = 128;

    /**
     * 3 to 32 of {@code A-Z a-z 0-9 _ -}. ASCII alone: the platform's lookup once compared accent-insensitively, and
     * "ada" with an acute on its first letter logged into "ada" (04 §1); a rule that admits no accent makes no second
     * spelling of a name.
     */
    public static boolean validUsername(String username) {
        if (username == null || username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char ch = username.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * The one spelling of a username that names an account, which the limits count against; null for a name no
     * account can have. Usernames are ASCII, so lower case here is the column's own comparison.
     */
    public static String usernameKey(String username) {
        return validUsername(username) ? username.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 8 to 128 characters, and no half of a surrogate pair on its own: JSON can carry one ({@code "\ud800"}), and
     * hashing converts the password to UTF-8, which cannot encode it (the platform's 500, 04 §1).
     */
    public static boolean validPassword(char[] password) {
        if (password == null || password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
            return false;
        }
        for (int i = 0; i < password.length; i++) {
            if (Character.isHighSurrogate(password[i]) && i + 1 < password.length
                    && Character.isLowSurrogate(password[i + 1])) {
                i++;
            } else if (Character.isSurrogate(password[i])) {
                return false;
            }
        }
        return true;
    }

    private CredentialRules() {
    }
}
