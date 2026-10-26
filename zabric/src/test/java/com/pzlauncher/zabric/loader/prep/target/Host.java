package com.pzlauncher.zabric.loader.prep.target;

/** Loaded before the weave; its member types are not. */
public class Host {
    FieldType field;
    // a target too, loaded only by preparing this one
    Second second;

    public ReturnType run(ParamType p) {
        return null;
    }

    static final class Member {
    }
}

class FieldType {
}

class ParamType {
}

class ReturnType {
}

class Second {
    SecondField field;

    public void run() {
    }
}

class SecondField {
}

class Unloaded {
    UnloadedField field;

    public UnloadedReturn run(UnloadedParam[] p) throws UnloadedException {
        return null;
    }

    static final class Member {
    }
}

class UnloadedField {
}

class UnloadedParam {
}

class UnloadedReturn {
}

class UnloadedException extends Exception {
}
