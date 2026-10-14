package com.pzlauncher.zabric.processor.glue;

import com.pzlauncher.zabric.processor.glue.GlueModel.At;
import com.pzlauncher.zabric.processor.glue.GlueModel.Block;
import com.pzlauncher.zabric.processor.glue.GlueModel.Call;
import com.pzlauncher.zabric.processor.glue.GlueModel.Patch;
import com.pzlauncher.zabric.processor.glue.GlueModel.Share;
import com.pzlauncher.zabric.processor.glue.GlueModel.Value;
import com.pzlauncher.zabric.processor.glue.HookDecls.Kind;
import freemarker.template.Configuration;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link GlueModel.Unit}'s glue source and listings, in the shape of Zabric's reference glue
 * ({@code zabric/src/test/java/com/pzlauncher/zabric/hook/weave/glue/WeaveGlue.java}). This class picks names and
 * shapes; the Java syntax is in {@code glue.ftl} and {@code listing.ftl}.
 */
public final class GlueWriter {
    private static final int WIDTH = 120;
    private static final String CONTINUATION = "        ";
    /** Written by the templates between call arguments, where {@link #wrap} may break the line. */
    private static final char BREAK = '\u001f';

    private static final Configuration TEMPLATES = templates();

    private GlueWriter() {
    }

    public static String listingPath(String hookClassBinaryName) {
        return Names.LISTING_DIR + hookClassBinaryName + Names.LISTING_SUFFIX;
    }

    /** By resource path. */
    public static Map<String, String> listings(GlueModel.Unit unit) {
        Map<String, String> listings = new LinkedHashMap<>();
        for (GlueModel.Listing listing : unit.listings()) {
            listings.put(listingPath(listing.hookClass()),
                    render("listing.ftl", Map.of("unit", unit, "listing", listing)));
        }
        return listings;
    }

    public static String source(GlueModel.Unit unit) {
        List<PatchView> patches = unit.patches().stream().map(GlueWriter::view).toList();
        return wrap(render("glue.ftl", Map.of("unit", unit, "patches", patches, "br", String.valueOf(BREAK))));
    }

    // public so that FreeMarker can call the accessors
    public record PatchView(String name, String owner, String method, boolean nonVoid, boolean cancellable,
                            boolean hasReturn, boolean onThrowable, boolean restoreValue, List<Param> enterParams,
                            List<Param> exitParams, List<BlockView> blocks) {
        public boolean exit() {
            return !exitParams.isEmpty();
        }
    }

    /** {@code kind}: self, arg, return, thrown or local. */
    public record Param(String kind, int index, boolean writable, String type, String name) {
        static Param self(String type) {
            return new Param("self", -1, false, type, "self");
        }

        static Param arg(int index, boolean writable, String type) {
            return new Param("arg", index, writable, type, "arg" + index);
        }

        static Param ret(boolean writable, String type) {
            return new Param("return", -1, writable, type, "ret");
        }

        static Param thrown() {
            return new Param("thrown", -1, false, "Throwable", "thrown");
        }

        static Param local(String name, String type) {
            return new Param("local", -1, false, type, name);
        }
    }

    public record BlockView(String hookClass, String callee, String gate, boolean always, String gateLocal,
                            List<ShareView> shares, List<CallView> heads, List<CallView> exits) {
    }

    public record ShareView(String local, String type) {
    }

    /**
     * {@code assign}: what the handler's result replaces, else null; {@code declared}: the callback is a local
     * {@code ci}; {@code returned}: the callback's initial value, else null; {@code suffix}: of its typed
     * {@code getReturnValue}.
     */
    public record CallView(String handler, boolean head, boolean afterDone, String assign, String returnable,
                           boolean cancellable, boolean declared, String returned, String suffix,
                           List<ValueView> values) {
    }

    /** {@code cast}: the type to cast {@code name} to, else null; a callback has no name. */
    public record ValueView(boolean callback, String name, String cast) {
    }

    private static PatchView view(Patch patch) {
        GlueModel.Target target = patch.target();
        boolean nonVoid = !target.isVoid();
        List<Call> calls = patch.blocks().stream().flatMap(b -> b.calls().stream()).toList();
        boolean cancellableHead = calls.stream().anyMatch(c -> c.at() == At.HEAD && c.cancellable());
        boolean hasFinally = calls.stream().anyMatch(c -> c.at() == At.FINALLY);
        boolean hasReturn = calls.stream().anyMatch(c -> c.at() == At.RETURN);
        boolean hasExit = hasFinally || hasReturn || cancellableHead && nonVoid;

        List<BlockView> blocks = new ArrayList<>();
        for (int n = 0; n < patch.blocks().size(); n++) {
            Block block = patch.blocks().get(n);
            List<ShareView> shares = new ArrayList<>();
            for (Share share : block.shares()) {
                shares.add(new ShareView(shareLocal(n, share.key()), share.type()));
            }
            List<CallView> heads = new ArrayList<>();
            List<CallView> exits = new ArrayList<>();
            for (Call call : block.calls()) {
                (call.exits() ? exits : heads).add(call(n, target, call));
            }
            blocks.add(new BlockView(block.hookClass(), block.callee(), block.gate(), block.always(),
                    gateLocal(n, block), shares, heads, exits));
        }

        List<Param> enter = new ArrayList<>();
        if (calls.stream().anyMatch(c -> c.at() == At.HEAD && c.uses(Value.Kind.SELF))) {
            enter.add(Param.self(target.ownerSource()));
        }
        for (int k = 0; k < target.args().size(); k++) {
            GlueModel.Arg arg = target.args().get(k);
            enter.add(Param.arg(k, arg.writable(), arg.binding()));
        }
        for (BlockView block : blocks) {
            enter.add(Param.local(block.gateLocal(), "boolean"));
            block.shares().forEach(s -> enter.add(Param.local(s.local(), localRef(s.type()))));
        }
        if (cancellableHead) {
            enter.add(Param.local("cancelled", "boolean"));
            if (nonVoid) {
                enter.add(Param.local("value", target.returnType()));
            }
        }

        List<Param> exit = new ArrayList<>();
        if (hasExit) {
            boolean retWritable = cancellableHead || calls.stream().anyMatch(c -> c.exits()
                    && (c.kind() == Kind.MODIFY_RETURN_VALUE || c.at() == At.RETURN && c.cancellable()));
            if (calls.stream().anyMatch(c -> c.exits() && c.uses(Value.Kind.SELF))) {
                exit.add(Param.self(target.ownerSource()));
            }
            for (int k = 0; k < target.args().size(); k++) {
                exit.add(Param.arg(k, false, target.args().get(k).binding()));
            }
            if (nonVoid) {
                exit.add(Param.ret(retWritable, target.returnType()));
            }
            if (hasFinally) {
                exit.add(Param.thrown());
            }
            for (int n = 0; n < blocks.size(); n++) {
                List<Call> own = patch.blocks().get(n).calls().stream().filter(Call::exits).toList();
                if (own.isEmpty()) {
                    continue;
                }
                exit.add(Param.local(blocks.get(n).gateLocal(), "boolean"));
                for (Share share : patch.blocks().get(n).shares()) {
                    if (own.stream().anyMatch(c -> c.values().contains(new Value(Value.Kind.SHARE, -1, share.key())))) {
                        exit.add(Param.local(shareLocal(n, share.key()), localRef(share.type())));
                    }
                }
            }
            if (cancellableHead && (nonVoid || hasReturn)) {
                exit.add(Param.local("cancelled", "boolean"));
            }
            if (cancellableHead && nonVoid) {
                exit.add(Param.local("value", target.returnType()));
            }
        }
        return new PatchView(patch.name(), target.owner(), target.method(), nonVoid, cancellableHead, hasReturn,
                hasFinally, cancellableHead && nonVoid, enter, exit, blocks);
    }

    private static CallView call(int n, GlueModel.Target target, Call call) {
        String assign = switch (call.kind()) {
            case MODIFY_RETURN_VALUE -> "ret";
            case MODIFY_VARIABLE -> "arg" + call.modifiedArg();
            default -> null;
        };
        boolean returnable = call.returnable() != null;
        boolean declared = assign == null && (call.cancellable() || returnable && call.at() != At.HEAD);
        String returned = !declared || !returnable ? null : switch (call.at()) {
            case RETURN -> "ret";
            case FINALLY -> "returned";
            case HEAD -> null;
        };
        List<ValueView> values = new ArrayList<>();
        for (Value value : call.values()) {
            values.add(switch (value.kind()) {
                case SELF -> new ValueView(false, "self", null);
                case ARG -> {
                    GlueModel.Arg arg = target.args().get(value.index());
                    yield new ValueView(false, "arg" + value.index(),
                            arg.binding().equals(arg.type()) ? null : arg.type());
                }
                case CALLBACK -> new ValueView(true, null, null);
                case THROWN -> new ValueView(false, "thrown", null);
                case SHARE -> new ValueView(false, shareLocal(n, value.key()), null);
                case ORIGINAL -> new ValueView(false, assign, null);
            });
        }
        return new CallView(call.handler(), call.at() == At.HEAD, call.at() == At.RETURN, assign, call.returnable(),
                call.cancellable(), declared, returned, Names.GETTER_SUFFIXES.getOrDefault(target.returnType(), ""),
                values);
    }

    private static String gateLocal(int n, Block block) {
        return "h" + n + (block.always() ? "$open" : "$active");
    }

    private static String shareLocal(int n, String key) {
        return "h" + n + "$share$" + key;
    }

    private static String localRef(String type) {
        return "LocalRef<" + type + ">";
    }

    /** Breaks each line greedily at {@link #BREAK} to fit {@link #WIDTH}. */
    private static String wrap(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String[] pieces = line.split(String.valueOf(BREAK));
            String indent = line.substring(0, line.length() - line.stripLeading().length()) + CONTINUATION;
            StringBuilder current = new StringBuilder(pieces[0]);
            for (int i = 1; i < pieces.length; i++) {
                if (current.length() + 1 + pieces[i].length() > WIDTH) {
                    out.append(current).append('\n');
                    current = new StringBuilder(indent).append(pieces[i]);
                } else {
                    current.append(' ').append(pieces[i]);
                }
            }
            out.append(current).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    private static String render(String template, Map<String, Object> model) {
        StringWriter out = new StringWriter();
        try {
            TEMPLATES.getTemplate(template).process(model, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (TemplateException e) {
            throw new IllegalStateException("rendering " + template, e);
        }
        return out.toString();
    }

    private static Configuration templates() {
        Configuration config = new Configuration(Configuration.VERSION_2_3_34);
        config.setClassForTemplateLoading(GlueWriter.class, "");
        config.setDefaultEncoding("UTF-8");
        config.setLocale(Locale.ROOT);
        config.setLocalizedLookup(false);
        config.setNumberFormat("computer");
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        config.setFallbackOnNullLoopVariable(false);
        return config;
    }
}
