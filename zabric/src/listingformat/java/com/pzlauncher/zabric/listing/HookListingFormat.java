package com.pzlauncher.zabric.listing;

/**
 * Where a hook listing lives and the fixed words in it. Compiled into Zabric, its processor and the build logic, which
 * do not link against Zabric, so plain JDK only.
 */
public final class HookListingFormat {
    public static final String DIR = "META-INF/zabric/hooks/";
    public static final String SUFFIX = ".txt";
    /** A change old readers must understand takes a new version. */
    public static final String HEADER = "zabric-hooks v1";
    public static final String DEFAULT_GATE = "default";

    private HookListingFormat() {
    }
}
