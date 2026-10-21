package com.pzlauncher.zabric.hook.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TargetCheckTest {
    // JDK targets: loading a weave test class here would load it before its glue is applied
    private static final ClassLoader LOADER = TargetCheckTest.class.getClassLoader();

    @Test
    void matchingTargetsAreNoProblem() {
        assertNull(TargetCheck.problem(listing("java.lang.String", "java.lang.String <init> ()V",
                "java.lang.String length ()I"), LOADER));
    }

    @Test
    void namesEveryTargetThatDoesNotMatch() {
        assertEquals("the targets of a.Hooks (src) do not match: its patch class a.NoPatch is not there; class"
                        + " a.NoClass is not there; java.lang.String has no method nope; java.lang.String has no"
                        + " length(I)I (it has length()I); java.lang.Object has no <init>(I)V (it has <init>()V)",
                TargetCheck.problem(listing("a.NoPatch", "a.NoClass run ()V", "java.lang.String nope ()V",
                        "java.lang.String length (I)I", "java.lang.Object <init> (I)V"), LOADER));
    }

    @Test
    void beforeTheGameClassesAreFoundByTheirFilesOnly() {
        assertNull(TargetCheck.presenceProblem(listing("java.lang.String", "java.lang.String length ()I"), LOADER));
        assertEquals("the targets of a.Hooks (src) do not match: its patch class a.NoPatch is not there; class"
                        + " a.NoClass is not there",
                TargetCheck.presenceProblem(listing("a.NoPatch", "a.NoClass run ()V", "java.lang.String nope ()V"),
                        LOADER));
    }

    @Test
    void fromBytesNamesTheSameMismatchesForThatClassOnly() throws IOException {
        byte[] string;
        try (InputStream in = LOADER.getResourceAsStream("java/lang/String.class")) {
            string = in.readAllBytes();
        }
        assertNull(TargetCheck.problem(listing("java.lang.String", "java.lang.String <init> ()V",
                "java.lang.String length ()I"), "java.lang.String", string));
        assertEquals("the targets of a.Hooks (src) do not match: java.lang.String has no method nope;"
                        + " java.lang.String has no length(I)I (it has length()I); java.lang.String has no method"
                        + " <clinit>",
                TargetCheck.problem(listing("a.NoPatch", "a.NoClass run ()V", "java.lang.String nope ()V",
                        "java.lang.String length (I)I", "java.lang.String <clinit> ()V",
                        "java.lang.Object <init> (I)V"), "java.lang.String", string));
    }

    // hooks: "owner method descriptor"
    private static HookListing listing(String patch, String... hooks) {
        List<String> lines = new ArrayList<>(List.of(HookListing.HEADER, "mod m", "gate default", "patch " + patch));
        for (String hook : hooks) {
            lines.add("hook " + hook + " " + patch);
        }
        return HookListing.parse("src", "a.Hooks", lines);
    }
}
