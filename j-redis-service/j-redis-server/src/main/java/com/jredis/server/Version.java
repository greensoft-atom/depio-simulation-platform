package com.jredis.server;

import com.jredis.common.BuildVersion;

/** Build version, reported by INFO, HELLO and --version. Set in the POMs only. */
public final class Version {

    public static final String VERSION = BuildVersion.VERSION;

    private Version() {
    }
}
