package ibuprofen.telemetry;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Lightweight, async, gzip-compressed JSONL debug recorder.
 * Built strictly for answering "what happened and why?" during development and live testing
 * without multi-gigabyte ML logs or main-thread disk I/O stalls.
 */
public final class DebugRecorder {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int QUEUE_CAPACITY = 4096;
    private static final long MAX_UNCOMPRESSED_BYTES = 32L * 1024L * 1024L; // ~2-3 MB gzipped cap per session

    private final Path logDir;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicLong uncompressedBytes = new AtomicLong(0);
    private final AtomicLong droppedLines = new AtomicLong(0);
    private final AtomicLong lineCount = new AtomicLong(0);

    private volatile Path currentFile;
    private volatile Thread writerThread;
    private long lastHeartbeatTick = -100;

    public DebugRecorder(Path baseDir) {
        this.logDir = baseDir.resolve("ibuprofen").resolve("debug_logs");
    }

    public synchronized Path startSession(String tag) {
        if (recording.get()) {
            return currentFile;
        }
        try {
            Files.createDirectories(logDir);
            String safeTag = sanitizeTag(tag);
            String name = "ibu-" + LocalDateTime.now().format(TS_FMT) + "-" + safeTag + ".jsonl.gz";
            Path target = logDir.resolve(name);
            currentFile = target;
            queue.clear();
            uncompressedBytes.set(0);
            droppedLines.set(0);
            lineCount.set(0);
            lastHeartbeatTick = -100;
            recording.set(true);

            Thread t = new Thread(() -> runWriterLoop(target), "ibuprofen-debug-writer");
            t.setDaemon(true);
            writerThread = t;
            t.start();

            logEvent(0, "session_start", "Recording started", "tag=" + safeTag);
            return target;
        } catch (IOException e) {
            recording.set(false);
            currentFile = null;
            return null;
        }
    }

    public synchronized void stopSession(String reason) {
        if (!recording.get()) {
            return;
        }
        logEvent(0, "session_end", "Recording stopped", reason != null ? reason : "manual");
        recording.set(false);
        Thread t = writerThread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        writerThread = null;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public Path currentFile() {
        return currentFile;
    }

    public long uncompressedBytes() {
        return uncompressedBytes.get();
    }

    public long lineCount() {
        return lineCount.get();
    }

    public long droppedLines() {
        return droppedLines.get();
    }

    public void logEvent(long tick, String type, String summary, String detail) {
        if (!recording.get()) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"t\":").append(tick)
                .append(",\"ts\":").append(System.currentTimeMillis())
                .append(",\"k\":\"").append(esc(type))
                .append("\",\"msg\":\"").append(esc(summary)).append('"');
        if (detail != null && !detail.isEmpty()) {
            sb.append(",\"d\":\"").append(esc(detail)).append('"');
        }
        sb.append('}');
        enqueue(sb.toString());
    }

    public void logChannelChange(long tick, String channel, String fromOwner, String toOwner, String reason) {
        if (!recording.get()) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"t\":").append(tick)
                .append(",\"k\":\"ch\",\"ch\":\"").append(esc(channel))
                .append("\",\"from\":\"").append(esc(fromOwner != null ? fromOwner : "human"))
                .append("\",\"to\":\"").append(esc(toOwner != null ? toOwner : "human"))
                .append("\",\"why\":\"").append(esc(reason)).append("\"}");
        enqueue(sb.toString());
    }

    public void logSkillOutcome(long tick, String skill, String outcome, String reason) {
        if (!recording.get()) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"t\":").append(tick)
                .append(",\"k\":\"skill\",\"s\":\"").append(esc(skill))
                .append("\",\"out\":\"").append(esc(outcome))
                .append("\",\"why\":\"").append(esc(reason)).append("\"}");
        enqueue(sb.toString());
    }

    public void logSlowTick(long tick, double elapsedMs, String detail) {
        if (!recording.get()) {
            return;
        }
        StringBuilder sb = new StringBuilder(140);
        sb.append("{\"t\":").append(tick)
                .append(",\"k\":\"slow\",\"ms\":").append(String.format(java.util.Locale.ROOT, "%.2f", elapsedMs))
                .append(",\"d\":\"").append(esc(detail)).append("\"}");
        enqueue(sb.toString());
    }

    /**
     * Compact periodic state heartbeat: 2 Hz when idle, 10 Hz when any bot channel is active.
     */
    public void maybeLogHeartbeat(
            long tick,
            boolean botActive,
            double x, double y, double z,
            float yaw, float pitch, float hp,
            String phase, String team,
            int enemiesNear, String channelsSummary) {
        if (!recording.get()) {
            return;
        }
        long interval = botActive ? 2L : 10L;
        if (tick - lastHeartbeatTick < interval) {
            return;
        }
        lastHeartbeatTick = tick;

        StringBuilder sb = new StringBuilder(220);
        sb.append("{\"t\":").append(tick)
                .append(",\"k\":\"hb\"")
                .append(",\"p\":[").append(round1(x)).append(',').append(round1(y)).append(',').append(round1(z)).append(']')
                .append(",\"r\":[").append(round1(yaw)).append(',').append(round1(pitch)).append(']')
                .append(",\"hp\":").append(round1(hp))
                .append(",\"ph\":\"").append(esc(phase))
                .append("\",\"tm\":\"").append(esc(team))
                .append("\",\"en\":").append(enemiesNear)
                .append(",\"ch\":\"").append(esc(channelsSummary)).append("\"}");
        enqueue(sb.toString());
    }

    private void enqueue(String jsonLine) {
        if (uncompressedBytes.get() >= MAX_UNCOMPRESSED_BYTES) {
            droppedLines.incrementAndGet();
            return;
        }
        if (!queue.offer(jsonLine)) {
            droppedLines.incrementAndGet();
        }
    }

    private void runWriterLoop(Path target) {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(target), 16384)),
                StandardCharsets.UTF_8)) {
            while (recording.get() || !queue.isEmpty()) {
                String line = queue.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    writer.write(line);
                    writer.write('\n');
                    uncompressedBytes.addAndGet(line.length() + 1L);
                    lineCount.incrementAndGet();
                }
            }
            writer.flush();
        } catch (IOException | InterruptedException ignored) {
            // Best-effort debug telemetry: never crash Minecraft on disk error
        }
    }

    private static String round1(double v) {
        return Double.toString(Math.round(v * 10.0) / 10.0);
    }

    private static String sanitizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return "match";
        }
        String clean = tag.replaceAll("[^a-zA-Z0-9_-]", "_");
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c >= 0x20) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
