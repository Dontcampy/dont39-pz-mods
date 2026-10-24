package com.pzlauncher.zabric.loader.members;

/** Defined by the test's loader, which refuses {@link Missing}. */
public class Holder {
    Missing missing;
}

class Missing {
}

class Good {
    GoodField field;
}

class GoodField {
}
