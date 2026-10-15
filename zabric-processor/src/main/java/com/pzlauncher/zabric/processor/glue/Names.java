package com.pzlauncher.zabric.processor.glue;

import java.util.Map;

/** Names of Zabric's and ZombieBuddy's types; the processor does not link against either. */
public final class Names {
    private Names() {
    }

    public static final String HOOK_PACKAGE = "com.pzlauncher.zabric.hook";
    public static final String HOOKS = HOOK_PACKAGE + ".Hooks";
    public static final String INJECT = HOOK_PACKAGE + ".Inject";
    public static final String MODIFY_RETURN_VALUE = HOOK_PACKAGE + ".ModifyReturnValue";
    public static final String MODIFY_VARIABLE = HOOK_PACKAGE + ".ModifyVariable";
    public static final String AT = HOOK_PACKAGE + ".At";
    public static final String SELF = HOOK_PACKAGE + ".Self";
    public static final String SHARE = HOOK_PACKAGE + ".Share";
    public static final String THROWN = HOOK_PACKAGE + ".Thrown";
    public static final String UNSAFE_LOADER_PATCH = HOOK_PACKAGE + ".UnsafeLoaderPatch";
    public static final String HOOK_GATE = HOOK_PACKAGE + ".HookGate";
    public static final String CALLBACK_INFO = HOOK_PACKAGE + ".CallbackInfo";
    public static final String CALLBACK_INFO_RETURNABLE = HOOK_PACKAGE + ".CallbackInfoReturnable";
    public static final String LOCAL_REF = HOOK_PACKAGE + ".LocalRef";

    public static final String PATCH = "me.zed_0xff.zombie_buddy.Patch";

    public static final String OBJECT = "java.lang.Object";
    public static final String THROWABLE = "java.lang.Throwable";

    /** Processor options of the javac front end. */
    public static final String OPTION_MOD_ID = "zabric.modId";
    public static final String OPTION_JAVA_PKG_NAME = "zabric.javaPkgName";
    public static final String OPTION_GLUE_CLASS = "zabric.glueClass";

    /** Listing format: {@code com.pzlauncher.zabric.hook.impl.HookListing}. */
    public static final String LISTING_DIR = "META-INF/zabric/hooks/";
    public static final String LISTING_SUFFIX = ".txt";
    public static final String LISTING_DEFAULT_GATE = "default";

    /** Primitive type names to their boxes and to the suffix of CallbackInfoReturnable's typed getter. */
    static final Map<String, String> BOXES = Map.of("boolean", "java.lang.Boolean", "byte", "java.lang.Byte",
            "char", "java.lang.Character", "short", "java.lang.Short", "int", "java.lang.Integer", "long",
            "java.lang.Long", "float", "java.lang.Float", "double", "java.lang.Double");
    static final Map<String, String> GETTER_SUFFIXES = Map.of("boolean", "Z", "byte", "B", "char", "C", "short", "S",
            "int", "I", "long", "J", "float", "F", "double", "D");

    static boolean isPrimitive(String erasedName) {
        return BOXES.containsKey(erasedName);
    }
}
