package com.dont39.pz.loadingprogress.report;

import com.dont39.pz.loadingprogress.progress.AssetWait;
import com.dont39.pz.loadingprogress.progress.FileWork;
import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import com.dont39.pz.loadingprogress.progress.Timed;
import com.dont39.pz.loadingprogress.text.Strings;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** A flow's timing report: a summary to read and a CSV with a row per line of the flow's log. Game-free. */
public final class Report {
    static final int SLOWEST = 20;
    static final String CSV_HEADER = "flow,step,kind,index,total,name,mod,start_ms,duration_ms,task_type,worker_ms,"
            + "task_failed";

    // the kinds whose time counts for the mod they belong to, in the order the per-mod lines name them
    private static final List<Item> MOD_KINDS = List.of(Item.JAVA_MOD, Item.MOD, Item.LUA, Item.SCRIPT);
    private static final Set<Item> FILE_KINDS = Set.of(Item.LUA, Item.SCRIPT);
    private static final String SCRIPTS_PROCESSED = "and the game's processing of all script files after it";
    // the game's log has the wall clock
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * What the report takes of the game when the flow ended. {@code endLine}: the flow's line in {@code console.txt};
     * {@code gameFolder}: the game's install folder; {@code modFolders}: each mod of the mod list with its folder (null
     * when the game has none).
     */
    public record Context(String endLine, String gameVersion, String modVersion, String gameFolder,
                          Map<String, String> modFolders) {
    }

    private Report() {
    }

    public static String summary(Progress.End end, Context context) {
        ModFolders folders = new ModFolders(context.modFolders(), context.gameFolder());
        StringBuilder out = new StringBuilder();
        out.append("Loading Progress ").append(context.modVersion()).append(": load timing report\n");
        out.append("Load: ").append(end.flow().label()).append(", ").append(outcome(end)).append('\n');
        out.append("Game ").append(context.gameVersion()).append("; ")
                .append(counted(context.modFolders().size(), "mod")).append(" in the mod list; ")
                .append(javaMods(end)).append('\n');
        out.append("Total ").append(ms(end.nanos())).append(", of it waiting in the menu ")
                .append(ms(end.waitedNanos())).append('\n');
        out.append("console.txt: ").append(context.endLine()).append('\n');

        out.append("\nSteps\n");
        Map<Step, Long> steps = new EnumMap<>(Step.class);
        Map<Step, Long> outsideItems = new EnumMap<>(Step.class);
        Set<Step> withItems = EnumSet.noneOf(Step.class);
        end.timed().forEach(timed -> {
            steps.merge(timed.line().step(), timed.nanos(), Long::sum);
            if (timed.line().isStep()) {
                outsideItems.merge(timed.line().step(), timed.nanos(), Long::sum);
            } else {
                withItems.add(timed.line().step());
            }
        });
        List<Step> order = end.flow().steps();
        for (int i = 0; i < order.size(); i++) {
            Step step = order.get(i);
            Long nanos = steps.get(step);
            if (nanos != null) {
                row(out, "  ", nanos, (i + 1) + " / " + order.size() + "  " + Strings.step(step, false)
                        + (withItems.contains(step)
                        ? "  (outside its items " + ms(outsideItems.getOrDefault(step, 0L)) + ")" : ""));
            }
        }

        if (end.assetWait() != null) {
            assetWait(out, end.assetWait(), folders);
        }

        Map<Item, List<Timed>> kinds = new EnumMap<>(Item.class);
        end.timed().stream().filter(timed -> !timed.line().isStep())
                .forEach(timed -> kinds.computeIfAbsent(timed.line().item(), kind -> new ArrayList<>()).add(timed));
        out.append("\nItems by kind: count, total time and the ").append(SLOWEST).append(" slowest\n");
        kinds.forEach((kind, items) -> {
            out.append("  ").append(kindLabel(kind)).append(": ").append(items.size()).append(", ")
                    .append(ms(sum(items))).append('\n');
            items.stream().sorted(Comparator.comparingLong(Timed::nanos).reversed()).limit(SLOWEST)
                    .forEach(timed -> row(out, "    ", timed.nanos(), itemText(timed, folders)));
        });
        if (steps.containsKey(Step.LOADING_WORLD)) {
            out.append("Times of the world load's items run from one line to the next: its threads load at once.\n");
        }

        out.append("\nTime per mod: its Java mod, its load in the mod list, and the Lua and script files in its")
                .append(" folder\n");
        Map<String, Map<Item, Long>> mods = new HashMap<>();
        long outside = 0;
        long lastScript = 0;
        for (Timed timed : end.timed()) {
            String mod = folders.modOf(timed);
            if (lastScript(timed.line())) {
                lastScript += timed.nanos();
            } else if (mod != null) {
                mods.computeIfAbsent(mod, id -> new EnumMap<>(Item.class))
                        .merge(timed.line().item(), timed.nanos(), Long::sum);
            } else if (!timed.line().isStep() && FILE_KINDS.contains(timed.line().item())) {
                outside += timed.nanos();
            }
        }
        mods.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Map<Item, Long>> mod) -> total(mod.getValue()))
                        .reversed().thenComparing(Map.Entry::getKey))
                .forEach(mod -> row(out, "  ", total(mod.getValue()), mod.getKey() + "  (" + parts(mod.getValue())
                        + ")"));
        row(out, "  ", outside, "Lua and script files in no mod's folder (the game's own)");
        if (lastScript > 0) {
            row(out, "  ", lastScript, "the last script file " + SCRIPTS_PROCESSED + ", in no mod");
        }
        return out.toString();
    }

    /**
     * A header and a row per line of the flow's log, steps included, so a step's rows add up to its time; then a row per
     * file task of the asset wait, whose times overlap.
     */
    public static String csv(Progress.End end, Context context) {
        ModFolders folders = new ModFolders(context.modFolders(), context.gameFolder());
        String flow = end.flow().name().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(CSV_HEADER).append("\r\n");
        for (Timed timed : end.timed()) {
            Line line = timed.line();
            String name = timed.path() != null ? timed.path()
                    : line.isStep() ? Strings.step(line.step(), false) : line.name();
            String mod = folders.modOf(timed);
            out.append(String.join(",", flow, line.step().name().toLowerCase(Locale.ROOT),
                    line.isStep() ? "step" : line.item().name().toLowerCase(Locale.ROOT),
                    Integer.toString(line.index()), Integer.toString(line.total()), field(name), field(mod),
                    millis(timed.start()), millis(timed.nanos()), "", "", "")).append("\r\n");
        }
        if (end.assetWait() != null) {
            String step = Step.LOADING_WORLD.name().toLowerCase(Locale.ROOT);
            for (FileWork.Run run : end.assetWait().runs()) {
                boolean queued = run.queued() >= 0;
                out.append(String.join(",", flow, step, "file_task", "0", "0", field(run.name()),
                        field(folders.modOfPath(run.name())), queued ? millis(run.queued()) : "",
                        queued ? millis(run.done() - run.queued()) : "", field(run.type()),
                        run.workerNanos() >= 0 ? millis(run.workerNanos()) : "", run.failed() ? "failed" : ""))
                        .append("\r\n");
            }
        }
        return out.toString();
    }

    static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // the wait as the game logs it, its frames and queue, and the file tasks done during it
    private static void assetWait(StringBuilder out, AssetWait wait, ModFolders folders) {
        out.append("\nAsset wait: the world load waits until the game's file tasks are done")
                .append(" (bWaitForAssetLoadingToFinish2 in the game's log)\n");
        row(out, "  ", wait.nanos(), "from " + clock(wait.startMillis()) + (wait.ended()
                ? " to " + clock(wait.endMillis()) : ", until the load ended during it") + ", " + ms(wait.start())
                + " after the load began");
        out.append("  ").append(counted(wait.frames(), "frame")).append(" pumped the file queue")
                .append(wait.frames() == 0 ? "" : ", one per " + ms(wait.nanos() / wait.frames()) + " on average")
                .append('\n');
        out.append("  At its first frame ")
                .append(counted(wait.queuedPending() + wait.queuedAtWorkers(), "file task")).append(" queued: ")
                .append(wait.queuedPending()).append(" waiting for a worker, ").append(wait.queuedAtWorkers())
                .append(" at the workers\n");
        List<FileWork.Run> runs = wait.runs();
        out.append("  ").append(counted(runs.size(), "file task")).append(" done during it; ")
                .append(counted(wait.workers(), "worker thread")).append(" busy ").append(ms(wait.busyNanos()));
        if (wait.workers() > 0 && wait.nanos() > 0) {
            out.append(String.format(Locale.ROOT, " (%.0f%% of their time)",
                    100.0 * wait.busyNanos() / wait.workers() / wait.nanos()));
        }
        out.append('\n');
        out.append("  Texture decoders waited for the upload budget ").append(ms(wait.throttleNanos()))
                .append(", ").append(wait.throttledCalls()).append(" times longer than a moment; part of their")
                .append(" tasks' worker time\n");
        long untimed = runs.stream().filter(run -> run.workerNanos() < 0).count();
        if (untimed > 0) {
            out.append("  ").append(counted((int) untimed, "task")).append(" not timed on a worker: at the workers")
                    .append(" before the world load, cancelled, or of a class the game does not have\n");
        }

        out.append("  By type: worker time and tasks\n");
        Map<String, List<FileWork.Run>> types = new TreeMap<>();
        runs.forEach(run -> types.computeIfAbsent(run.type(), type -> new ArrayList<>()).add(run));
        types.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, List<FileWork.Run>> type) -> worker(type.getValue()))
                        .reversed())
                .forEach(type -> {
                    List<FileWork.Run> ofType = type.getValue();
                    List<String> notes = new ArrayList<>();
                    long failed = ofType.stream().filter(FileWork.Run::failed).count();
                    long notTimed = ofType.stream().filter(run -> run.workerNanos() < 0).count();
                    if (failed > 0) {
                        notes.add(failed + " failed");
                    }
                    if (notTimed > 0) {
                        notes.add(notTimed + " not timed");
                    }
                    row(out, "    ", worker(ofType), type.getKey() + ": " + counted(ofType.size(), "task")
                            + (notes.isEmpty() ? "" : " (" + String.join(", ", notes) + ")"));
                });

        out.append("  The ").append(SLOWEST).append(" slowest tasks on a worker\n");
        runs.stream().filter(run -> run.workerNanos() >= 0)
                .sorted(Comparator.comparingLong(FileWork.Run::workerNanos).reversed()).limit(SLOWEST)
                .forEach(run -> {
                    String mod = folders.modOfPath(run.name());
                    row(out, "    ", run.workerNanos(), run.type() + "  " + run.name() + (mod == null ? ""
                            : "  [" + mod + "]") + (run.failed() ? "  (failed)" : ""));
                });

        out.append("  Worker time per mod, by the folder that holds the task's file\n");
        Map<String, List<FileWork.Run>> mods = new HashMap<>();
        List<FileWork.Run> game = new ArrayList<>();
        List<FileWork.Run> elsewhere = new ArrayList<>();
        List<FileWork.Run> unnamed = new ArrayList<>();
        runs.forEach(run -> {
            String mod = folders.modOfPath(run.name());
            if (mod != null) {
                mods.computeIfAbsent(mod, id -> new ArrayList<>()).add(run);
            } else if (folders.inGame(run.name())) {
                game.add(run);
            } else if (ModFolders.isFile(run.name())) {
                elsewhere.add(run);
            } else {
                unnamed.add(run);
            }
        });
        mods.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, List<FileWork.Run>> mod) -> worker(mod.getValue()))
                        .reversed().thenComparing(Map.Entry::getKey))
                .forEach(mod -> row(out, "    ", worker(mod.getValue()), mod.getKey() + "  ("
                        + counted(mod.getValue().size(), "task") + ")"));
        row(out, "    ", worker(game), "the game's own, in its folder  (" + counted(game.size(), "task") + ")");
        if (!elsewhere.isEmpty()) {
            row(out, "    ", worker(elsewhere), "in no mod's or the game's folder  ("
                    + counted(elsewhere.size(), "task") + ")");
        }
        row(out, "    ", worker(unnamed), "named without a file's path, so in no folder  ("
                + counted(unnamed.size(), "task") + ")");
    }

    private static long worker(List<FileWork.Run> runs) {
        return runs.stream().mapToLong(FileWork.Run::workerNanos).filter(nanos -> nanos >= 0).sum();
    }

    private static String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    private static String outcome(Progress.End end) {
        return end.outcome().name().toLowerCase(Locale.ROOT) + (end.why() == null ? "" : " (" + end.why() + ")");
    }

    private static String javaMods(Progress.End end) {
        int total = end.timed().stream().filter(timed -> timed.line().item() == Item.JAVA_MOD)
                .mapToInt(timed -> timed.line().total()).max().orElse(0);
        return total == 0 ? "no Java mod pass in this load" : counted(total, "Java mod") + " in its Java mod pass";
    }

    private static String counted(int count, String what) {
        return count + " " + what + (count == 1 ? "" : "s");
    }

    private static String itemText(Timed timed, ModFolders folders) {
        Line line = timed.line();
        String text = Strings.itemLine(line.item(), line.index(), line.total(), line.name(), false);
        if (text == null) {
            text = kindLabel(line.item()) + (line.total() == 0 ? "" : " " + line.index() + " / " + line.total());
        }
        if (lastScript(line)) {
            return text + "  (" + SCRIPTS_PROCESSED + ")";
        }
        String mod = FILE_KINDS.contains(line.item()) ? folders.modOf(timed) : null;
        return mod == null ? text : text + "  [" + mod + "]";
    }

    // no hook marks the end of the game's file loop before it processes the loaded scripts, so the last file's
    // time holds that processing
    private static boolean lastScript(Line line) {
        return line.item() == Item.SCRIPT && line.index() == line.total();
    }

    private static String parts(Map<Item, Long> kinds) {
        List<String> parts = new ArrayList<>();
        MOD_KINDS.forEach(kind -> {
            Long nanos = kinds.get(kind);
            if (nanos != null) {
                parts.add(kindLabel(kind) + " " + ms(nanos));
            }
        });
        return String.join(", ", parts);
    }

    private static String kindLabel(Item kind) {
        return switch (kind) {
            case NONE -> "none";
            case JAVA_MOD -> "Java mod";
            case MOD -> "mod";
            case SCRIPT -> "script files";
            case LUA -> "Lua files";
            case TEXTURE_PACK -> "texture packs";
            case EVENT -> "Lua events";
            case WORLD -> "world-loading text";
            case WORLD_EVENT -> "world-loading Lua events";
            case CLOTHING_ITEM -> "clothing items";
            case MAP_CELL -> "map cells";
            case TILE_DEFINITIONS -> "tile definitions";
            case CHUNK -> "chunks";
            case ANIMATION -> "animations";
            case ASSET_WAIT -> "asset wait";
        };
    }

    private static void row(StringBuilder out, String indent, long nanos, String text) {
        out.append(indent).append(String.format(Locale.ROOT, "%9s", ms(nanos))).append("  ").append(text)
                .append('\n');
    }

    private static long sum(List<Timed> items) {
        return items.stream().mapToLong(Timed::nanos).sum();
    }

    private static long total(Map<Item, Long> kinds) {
        return kinds.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String ms(long nanos) {
        return Math.round(nanos / 1e6) + " ms";
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }
}
