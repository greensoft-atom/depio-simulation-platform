package com.backend.account;

/** A hasher of the policy's shape at a fraction of its cost, for tests that hash many times. */
public final class PasswordHashers {

    public static PasswordHasher cheap() {
        return new PasswordHasher(1024, 1, 1);
    }

    private PasswordHashers() {
    }
}
