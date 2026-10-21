package com.dont39.pz.loadingprogress.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The report files of the last {@value #KEEP} loads, a summary and a CSV each. Game-free. */
public final class Reports {
    public static final String FOLDER = "Dont39LoadingProgress";
    public static final int KEEP = 10;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    // only files of this form are ever deleted
    private static final Pattern FILE =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_[a-z]+(?:_\\d+)?)\\.(?:txt|csv)");
    // Excel reads a CSV without it in the system's code page, which garbles Chinese names
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Reports() {
    }

    /**
     * Writes a load's {@code summary} and {@code csv} into {@code folder}, named by {@code time} and {@code flow}, then
     * deletes all but the newest {@value #KEEP} loads. Returns the summary's path.
     */
    public static Path write(Path folder, LocalDateTime time, String flow, String summary, String csv)
            throws IOException {
        Files.createDirectories(folder);
        String base = time.format(TIME) + "_" + flow;
        String name = base;
        for (int n = 2; Files.exists(folder.resolve(name + ".txt")) || Files.exists(folder.resolve(name + ".csv"));
             n++) {
            name = base + "_" + n;
        }
        Path text = folder.resolve(name + ".txt");
        Files.writeString(text, summary, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        byte[] rows = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + rows.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(rows, 0, bytes, BOM.length, rows.length);
        Files.write(folder.resolve(name + ".csv"), bytes, StandardOpenOption.CREATE_NEW);
        prune(folder);
        return text;
    }

    private static void prune(Path folder) throws IOException {
        // the names sort by time; a clash's counter after its first
        TreeSet<String> loads = new TreeSet<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                Matcher matcher = FILE.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    loads.add(matcher.group(1));
                }
            }
        }
        while (loads.size() > KEEP) {
            String oldest = loads.pollFirst();
            Files.deleteIfExists(folder.resolve(oldest + ".txt"));
            Files.deleteIfExists(folder.resolve(oldest + ".csv"));
        }
    }
}
