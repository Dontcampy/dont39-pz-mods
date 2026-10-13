package com.dont39.pz.zabric.classtweaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A {@code .classtweaker} file: Fabric access widener directives ({@code accessible}, {@code mutable}) for classes,
 * fields and constructors, with internal names and JVM descriptors.
 * <p>
 * Shared by the build (buildSrc) and the game-start widening, so plain JDK only.
 */
public final class ClassTweaker {
    public static final String HEADER = "zabric-classtweaker v1";

    public enum Access {
        ACCESSIBLE, MUTABLE;

        String keyword() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Kind {
        CLASS, FIELD, METHOD;

        String keyword() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code name} and {@code descriptor} are null for a class. */
    public record Entry(String source, int line, Access access, Kind kind, String owner, String name, String descriptor) {
        public String directive() {
            String text = access.keyword() + " " + kind.keyword() + " " + owner;
            return kind == Kind.CLASS ? text : text + " " + name + " " + descriptor;
        }

        public String where() {
            return source + ":" + line + " '" + directive() + "'";
        }
    }

    private static final String SEGMENT = "[^.;\\[/<>\\s]+";
    private static final String INTERNAL = "(?:" + SEGMENT + "/)*" + SEGMENT;
    private static final String FIELD_DESC = "\\[*(?:[BCDFIJSZ]|L" + INTERNAL + ";)";
    private static final Pattern INTERNAL_NAME = Pattern.compile(INTERNAL);
    private static final Pattern MEMBER_NAME = Pattern.compile(SEGMENT);
    private static final Pattern FIELD_DESCRIPTOR = Pattern.compile(FIELD_DESC);
    private static final Pattern CONSTRUCTOR_DESCRIPTOR = Pattern.compile("\\((?:" + FIELD_DESC + ")*\\)V");

    private ClassTweaker() {
    }

    /** Throws {@link IllegalArgumentException} naming {@code source} and the line. */
    public static List<Entry> parse(String source, List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
            throw new IllegalArgumentException(source + ":1: the first line must be '" + HEADER + "'");
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String text = lines.get(i);
            int comment = text.indexOf('#');
            text = (comment < 0 ? text : text.substring(0, comment)).strip();
            if (!text.isEmpty()) {
                entries.add(entry(source, i + 1, text));
            }
        }
        return entries;
    }

    /** The header and one directive per line, readable by {@link #parse}. */
    public static String write(List<Entry> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            out.append(e.directive()).append('\n');
        }
        return out.toString();
    }

    private static Entry entry(String source, int line, String text) {
        String[] t = text.split("\\s+");
        String where = source + ":" + line + ": ";
        Access access = switch (t[0]) {
            case "accessible" -> Access.ACCESSIBLE;
            case "mutable" -> Access.MUTABLE;
            case "extendable" -> throw new IllegalArgumentException(where + "'extendable' is not supported; only"
                    + " 'accessible' and 'mutable' are");
            default -> throw new IllegalArgumentException(where + "unknown access '" + t[0] + "'; expected"
                    + " 'accessible' or 'mutable'");
        };
        Kind kind = t.length < 2 ? null : switch (t[1]) {
            case "class" -> Kind.CLASS;
            case "field" -> Kind.FIELD;
            case "method" -> Kind.METHOD;
            default -> null;
        };
        if (kind == null) {
            throw new IllegalArgumentException(where + "expected 'class', 'field' or 'method' after '" + t[0] + "'");
        }
        int expected = kind == Kind.CLASS ? 3 : 5;
        if (t.length != expected) {
            throw new IllegalArgumentException(where + "'" + t[0] + " " + t[1] + "' takes " + (expected - 2)
                    + (kind == Kind.CLASS ? " class name" : " values: owner, name, descriptor") + "; got '" + text
                    + "'");
        }
        if (access == Access.MUTABLE && kind != Kind.FIELD) {
            throw new IllegalArgumentException(where + "'mutable' applies to fields only");
        }
        if (!INTERNAL_NAME.matcher(t[2]).matches()) {
            throw new IllegalArgumentException(where + "'" + t[2] + "' is not an internal class name (a/b/C$D)");
        }
        if (kind == Kind.CLASS) {
            return new Entry(source, line, access, kind, t[2], null, null);
        }
        if (kind == Kind.METHOD) {
            // a visibility change on other methods can change virtual dispatch
            if (!t[3].equals("<init>")) {
                throw new IllegalArgumentException(where + "only constructors (<init>) can be widened, not method '"
                        + t[3] + "'");
            }
            if (!CONSTRUCTOR_DESCRIPTOR.matcher(t[4]).matches()) {
                throw new IllegalArgumentException(where + "'" + t[4] + "' is not a constructor descriptor (...)V");
            }
        } else {
            if (!MEMBER_NAME.matcher(t[3]).matches()) {
                throw new IllegalArgumentException(where + "'" + t[3] + "' is not a field name");
            }
            if (!FIELD_DESCRIPTOR.matcher(t[4]).matches()) {
                throw new IllegalArgumentException(where + "'" + t[4] + "' is not a field descriptor");
            }
        }
        return new Entry(source, line, access, kind, t[2], t[3], t[4]);
    }
}
