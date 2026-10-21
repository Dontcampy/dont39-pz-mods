package com.dont39.pz.loadingprogress.progress;

/**
 * A call that holds the game thread for seconds with no hookable point inside, so no own frame can come during it: a
 * known gap of R8. The frame-gap line says how much of a gap such calls took.
 */
public enum Known {
    /** One call per script-format file; vanilla's tile geometry alone takes about 2 s. */
    COMMENT_STRIPPING("script comment stripping"),
    /** Steam's list of installed Workshop items, at mod discovery. */
    STEAM_ITEMS("Steam's installed items query");

    private final String label;

    Known(String label) {
        this.label = label;
    }

    /** For the log. */
    public String label() {
        return label;
    }
}
