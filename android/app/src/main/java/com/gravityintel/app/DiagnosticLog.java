package com.gravityintel.app;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A verbose diagnostic event log, persisted to a file rather than kept only in
  * memory - that matters because the scenario most worth debugging (the service
  * getting killed unexpectedly) is exactly the one where an in-memory-only log would
  * be lost right when it's needed most. Writes are cheap appends, not a full
  * read-rewrite every call, since this logs frequently (every location update) and a
  * background service shouldn't be doing O(n) file I/O per second. The file is only
  * trimmed back down to MAX_LINES periodically (every COMPACT_INTERVAL appends), not
  * on every write. */
public final class DiagnosticLog {

    private DiagnosticLog() {}

    private static final String FILE_NAME = "diagnostic.log";
    private static final int MAX_LINES = 5000;
    private static final int COMPACT_INTERVAL = 200;
    private static int appendsSinceCompact = 0;

    public static synchronized void log(Context context, String tag, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "[" + timestamp + "] " + tag + ": " + message + "\n";
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(entry.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // best-effort logging - nothing further to do if the write fails
        }
        appendsSinceCompact++;
        if (appendsSinceCompact >= COMPACT_INTERVAL) {
            appendsSinceCompact = 0;
            compactIfNeeded(context);
        }
    }

    public static synchronized String getAllText(Context context) {
        StringBuilder sb = new StringBuilder();
        for (String line : readLines(context)) sb.append(line).append("\n");
        return sb.toString();
    }

    public static synchronized void clear(Context context) {
        writeLines(context, new ArrayList<>());
        appendsSinceCompact = 0;
    }

    private static void compactIfNeeded(Context context) {
        List<String> lines = readLines(context);
        if (lines.size() > MAX_LINES) {
            writeLines(context, lines.subList(lines.size() - MAX_LINES, lines.size()));
        }
    }

    private static List<String> readLines(Context context) {
        List<String> lines = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return lines;
        try (FileInputStream fis = new FileInputStream(file)) {
            StringBuilder sb = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                sb.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            for (String line : sb.toString().split("\n")) {
                if (!line.isEmpty()) lines.add(line);
            }
        } catch (IOException e) {
            // unreadable log file - start fresh rather than crash over it
        }
        return lines;
    }

    private static void writeLines(Context context, List<String> lines) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append("\n");
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // best-effort persistence - nothing further to do if the write fails
        }
    }
}
