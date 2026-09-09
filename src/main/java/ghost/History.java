package ghost;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the instance's own log, so "what happened while I was away" is one call.
 *
 * <p>Written after spending an hour on 2026-09-08 grepping eight days of logs by
 * hand to answer a question the game had already recorded: twenty-two golems
 * died of old age on the 4th, every one logged correctly at the time, and the
 * player found out four days later. The mod that records and the assistant that
 * explains had no connection between them. This is that connection.
 *
 * <p>Deliberately blunt. It does not parse mods' formats or pretend to
 * understand them - it buckets lines by severity and by a handful of patterns
 * worth waking someone for, counts them, and hands back the most recent few
 * verbatim. A log line quoted exactly is worth more than a summary that might
 * have lost the detail that mattered.
 *
 * <p><b>Bounded on purpose.</b> {@code latest.log} runs to several megabytes and
 * {@code debug.log} to twenty. Reading either whole on the server thread would
 * stall a tick to answer a question, so only the tail is read, and only a capped
 * number of lines come back.
 */
public final class History {

    private History() {
    }

    /** Most of the tail to read, in bytes. Enough for hours; cheap enough to be free. */
    private static final long TAIL_BYTES = 3L * 1024 * 1024;

    /** Hard cap on lines returned, whatever was asked for. */
    private static final int MAX_LINES = 60;

    /**
     * The buckets. Order matters: the first match wins, so the specific
     * patterns sit above the generic severity ones.
     */
    private static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        // Things this project has actually needed to look at, in the order it
        // needed them. Every one of these earned its place by being the answer
        // to a real question at some point.
        KINDS.put("golemGone", Pattern.compile("GOLEM-WATCH GONE"));
        KINDS.put("golemStuck", Pattern.compile("GOLEM-WATCH STUCK"));
        KINDS.put("golemRecovered", Pattern.compile("GOLEM-WATCH RECOVERED"));
        KINDS.put("golemRoster", Pattern.compile("headcount at world load|HEADCOUNT|GOLEMS LOST|RETIRED IN THE FIELD|RAISED \\|"));
        KINDS.put("golemRelease", Pattern.compile("released \\d+ golem"));
        KINDS.put("ghost", Pattern.compile("\\[Ghost/"));
        KINDS.put("throughput", Pattern.compile("THROUGHPUT"));
        KINDS.put("death", Pattern.compile("Named entity .* died|was slain|blew up"));
        KINDS.put("lag", Pattern.compile("Can't keep up|running behind|Skipping \\d+ tick|took \\d{4,}ms"));
        KINDS.put("chunk", Pattern.compile("Failed to save|chunk .*(error|corrupt)|Exception .*chunk"));
        KINDS.put("crash", Pattern.compile("/FATAL\\]|Crash report|Preparing crash"));
        KINDS.put("error", Pattern.compile("/ERROR\\]|Exception|Caused by:"));
        KINDS.put("warn", Pattern.compile("/WARN\\]"));
    }

    /** Timestamps look like [08Sep2026 14:48:21.874]. */
    private static final Pattern STAMP =
            Pattern.compile("^\\[(\\d{2}[A-Za-z]{3}\\d{4}) (\\d{2}:\\d{2}:\\d{2})");

    private static Path logs() {
        // ghost/ sits in the instance root, so its parent is what we want.
        return Sampler.dir().getParent().resolve("logs");
    }

    /** Read the last TAIL_BYTES of a file as lines, dropping a partial first one. */
    private static List<String> tail(Path p) throws Exception {
        List<String> out = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - TAIL_BYTES);
            f.seek(from);
            byte[] buf = new byte[(int) Math.min(len - from, TAIL_BYTES)];
            f.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            String[] lines = text.split("\r?\n");
            for (int i = (from > 0 ? 1 : 0); i < lines.length; i++) {
                out.add(lines[i]);
            }
        }
        return out;
    }

    private static String kindOf(String line) {
        for (Map.Entry<String, Pattern> e : KINDS.entrySet()) {
            if (e.getValue().matcher(line).find()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * @param kind  one bucket name, or null for everything noteworthy
     * @param match extra free-text filter, case-insensitive, or null
     * @param limit how many lines to hand back, capped at {@link #MAX_LINES}
     */
    public static Map<String, Object> read(String kind, String match, int limit,
                                           boolean includeWarnings) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path log = logs().resolve("latest.log");
        if (!Files.isReadable(log)) {
            out.put("ok", false);
            out.put("error", "no readable logs/latest.log at " + log);
            return out;
        }
        List<String> lines;
        try {
            lines = tail(log);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "could not read the log: " + e);
            return out;
        }

        String needle = match == null ? null : match.toLowerCase(java.util.Locale.ROOT);
        Map<String, Integer> counts = new TreeMap<>();
        List<String> hits = new ArrayList<>();
        String firstStamp = null;
        String lastStamp = null;

        for (String line : lines) {
            Matcher m = STAMP.matcher(line);
            if (m.find()) {
                if (firstStamp == null) {
                    firstStamp = m.group(1) + " " + m.group(2);
                }
                lastStamp = m.group(1) + " " + m.group(2);
            }
            String k = kindOf(line);
            if (k == null) {
                continue;
            }
            // "warn" is enormous and mostly mixin chatter; it is opt-in.
            if ("warn".equals(k) && !includeWarnings && !"warn".equals(kind)) {
                continue;
            }
            counts.merge(k, 1, Integer::sum);
            if (kind != null && !kind.equals(k)) {
                continue;
            }
            if (needle != null && !line.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                continue;
            }
            hits.add(line.length() > 300 ? line.substring(0, 300) + "..." : line);
        }

        int want = Math.max(1, Math.min(limit <= 0 ? 20 : limit, MAX_LINES));
        List<String> recent = hits.size() <= want
                ? hits : hits.subList(hits.size() - want, hits.size());

        out.put("ok", true);
        out.put("log", log.getFileName().toString());
        out.put("scannedLines", lines.size());
        out.put("covers", (firstStamp == null ? "?" : firstStamp)
                + " -> " + (lastStamp == null ? "?" : lastStamp));
        out.put("counts", counts);
        out.put("matched", hits.size());
        out.put("showing", recent.size());
        out.put("lines", new ArrayList<>(recent));
        if (!includeWarnings && !"warn".equals(kind)) {
            out.put("note", "plain WARN lines are counted but not listed - most are "
                    + "mixin chatter. Ask for kind \"warn\" or set warnings true.");
        }
        out.put("kinds", new ArrayList<>(KINDS.keySet()));
        return out;
    }
}
