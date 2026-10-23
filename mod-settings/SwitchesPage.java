package com.dont39.pz.modsettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks a mod's options page against its {@link Switches}: the spec its Lua passes to {@code Switches.page} (id, name,
 * file, each switch's key and description, in order) and the page's text in every language's {@code UI.json}. For the
 * mod's tests; {@code pz-switches} compiles it into them under the mod's own package.
 */
public final class SwitchesPage {
    private static final Pattern UI_KEY = Pattern.compile("\"(UI_[A-Za-z0-9_]+)\"\\s*:");

    private SwitchesPage() {
    }

    /**
     * Throws an {@link AssertionError} naming the first difference between {@code switches} and the page in
     * {@code optionsLua}, or a translation in a folder of {@code translate} that the page lacks or no longer uses.
     */
    public static void check(Switches<?> switches, Path optionsLua, Path translate) throws IOException {
        Map<?, ?> spec = spec(Files.readString(optionsLua, StandardCharsets.UTF_8), optionsLua);
        String id = string(spec, "id");
        same("file", switches.file(), string(spec, "file"));
        same("header", switches.header(), "# " + string(spec, "name")
                + ", read once at each launch of the game; also on the mod options page.");
        if (!(spec.get("switches") instanceof List<?> page)) {
            throw new AssertionError(optionsLua + ": no switches list");
        }
        List<? extends Switches.Key> keys = switches.keys();
        same("number of switches", keys.size(), page.size());
        Set<String> texts = new TreeSet<>(Set.of("UI_" + id + "_Options"));
        for (int i = 0; i < keys.size(); i++) {
            Switches.Key key = keys.get(i);
            if (!(page.get(i) instanceof Map<?, ?> entry)) {
                throw new AssertionError(optionsLua + ": switch " + (i + 1) + " is not a table");
            }
            same("key of switch " + (i + 1), key.key(), string(entry, "key"));
            same("description of " + key.key(), key.description(), string(entry, "description"));
            String text = "UI_" + id + "_" + string(entry, "text");
            if (!texts.add(text) || !texts.add(text + "Tooltip")) {
                throw new AssertionError(optionsLua + ": " + text + " twice");
            }
        }
        List<Path> languages;
        try (Stream<Path> list = Files.list(translate)) {
            languages = list.filter(Files::isDirectory).sorted().toList();
        }
        if (languages.isEmpty()) {
            throw new AssertionError("no languages in " + translate);
        }
        for (Path language : languages) {
            Path ui = language.resolve("UI.json");
            Set<String> found = new TreeSet<>();
            Matcher matcher = UI_KEY.matcher(Files.readString(ui, StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (matcher.group(1).startsWith("UI_" + id + "_")) {
                    found.add(matcher.group(1));
                }
            }
            same(ui + " (keys of UI_" + id + "_)", texts, found);
        }
    }

    private static void same(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": Java side " + expected + ", options page " + actual);
        }
    }

    private static String string(Map<?, ?> table, String field) {
        if (!(table.get(field) instanceof String value)) {
            throw new AssertionError("no string " + field + " in " + table);
        }
        return value;
    }

    // the table literal passed to Switches.page
    private static Map<?, ?> spec(String lua, Path file) {
        int call = lua.indexOf("Switches.page(");
        if (call < 0) {
            throw new AssertionError(file + ": no Switches.page call");
        }
        Parser parser = new Parser(lua, call + "Switches.page(".length(), file);
        if (!(parser.value() instanceof Map<?, ?> spec)) {
            throw new AssertionError(file + ": Switches.page's argument is not a table with fields");
        }
        return spec;
    }

    /** Lua table constructors of fields and list entries, string literals joined with {@code ..}, comments. */
    private static final class Parser {
        private final String s;
        private final Path file;
        private int i;

        Parser(String s, int i, Path file) {
            this.s = s;
            this.i = i;
            this.file = file;
        }

        // a table with named fields is a map, one with entries only a list
        Object value() {
            skip();
            if (peek() == '{') {
                return table();
            }
            StringBuilder text = new StringBuilder(literal());
            skip();
            while (s.startsWith("..", i)) {
                i += 2;
                skip();
                text.append(literal());
                skip();
            }
            return text.toString();
        }

        private Object table() {
            i++;
            Map<String, Object> fields = new LinkedHashMap<>();
            List<Object> entries = new ArrayList<>();
            while (true) {
                skip();
                if (peek() == '}') {
                    i++;
                    break;
                }
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                    i++;
                }
                String name = s.substring(start, i);
                skip();
                if (!name.isEmpty() && peek() == '=') {
                    i++;
                    fields.put(name, value());
                } else {
                    i = start;
                    entries.add(value());
                }
                skip();
                if (peek() == ',' || peek() == ';') {
                    i++;
                } else if (peek() != '}') {
                    throw fail("',' or '}'");
                }
            }
            if (!fields.isEmpty() && !entries.isEmpty()) {
                throw fail("a table of fields or of entries, not both");
            }
            return entries.isEmpty() ? fields : entries;
        }

        private String literal() {
            char quote = peek();
            if (quote != '"' && quote != '\'') {
                throw fail("a string or a table");
            }
            StringBuilder text = new StringBuilder();
            for (i++; peek() != quote; i++) {
                char c = peek();
                if (c == '\\') {
                    c = s.charAt(++i);
                    c = switch (c) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case '\\', '"', '\'' -> c;
                        default -> throw fail("a known escape");
                    };
                } else if (c == '\n') {
                    throw fail("the end of the string");
                }
                text.append(c);
            }
            i++;
            return text.toString();
        }

        private void skip() {
            while (i < s.length()) {
                if (Character.isWhitespace(s.charAt(i))) {
                    i++;
                } else if (s.startsWith("--", i)) {
                    int end = s.indexOf('\n', i);
                    i = end < 0 ? s.length() : end;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw fail("more text");
            }
            return s.charAt(i);
        }

        private AssertionError fail(String expected) {
            long line = s.substring(0, Math.min(i, s.length())).chars().filter(c -> c == '\n').count() + 1;
            return new AssertionError(file + " line " + line + ": expected " + expected);
        }
    }
}
