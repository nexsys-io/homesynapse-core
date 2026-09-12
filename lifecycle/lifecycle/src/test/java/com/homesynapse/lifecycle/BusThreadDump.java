/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FIX-2b-i (B) — the thread dump an await timeout leaves behind, so the next
 * OR-BUS-SILENT-DROP red names the blocked frame beside the resting checkpoint.
 * {@link #capture(Path)} asks the JDK running this test JVM for a dump that
 * INCLUDES virtual threads — {@code jcmd <pid> Thread.dump_to_file}, the one
 * JDK 21 dump that lists them ({@code Thread.getAllStackTraces()} is platform
 * threads only) — writes it under the caller's directory, and returns a filtered
 * rendering: every thread whose name starts with {@value #NAME_PREFIX} (the bus's
 * {@code hs-sub-<id>} virtual threads and their {@code hs-sub-read-<id>}
 * executors; persistence's {@code hs-read-<n>} and {@code hs-write-0}), plus
 * every thread — platform or virtual — whose top {@value #FRAMES_SHOWN} frames
 * mention {@value #FRAME_MARKER}. When {@code jcmd} is absent beside
 * {@code java.home}, cannot attach, or does not finish inside
 * {@value #JCMD_TIMEOUT_SECONDS} s, the fallback is
 * {@code Thread.getAllStackTraces()} (platform threads only) with the reason
 * stated. Never throws: an instrument never becomes the failure channel.
 *
 * <p>The grammar (grep-stable {@code subsystem.token: key=value} lines,
 * {@code \n}-joined, no trailing newline):</p>
 * <pre>
 * bus.thread_dump: threads=&lt;total&gt; shown=&lt;k&gt; file=&lt;path&gt;
 * bus.thread_dump: source=platform_only threads=&lt;total&gt; shown=&lt;k&gt;
 * bus.thread_dump_fallback: reason=&lt;why&gt;                  (fallback only, the second line)
 * bus.thread: name=&lt;n&gt; state=&lt;STATE|unknown&gt; virtual=&lt;true|false&gt; tid=&lt;id&gt;
 * bus.thread_frame: &lt;frame&gt;                                 (at most 12 per thread, top first)
 * </pre>
 * <p>JDK 21's plain dump carries no thread state, so {@code state} is the live
 * {@link Thread.State} of a platform thread read beside the dump; a virtual
 * thread's state is not reachable from outside the thread and reads
 * {@code unknown} — its frames say whether it is parked, blocked in a monitor,
 * or running. Reading a red: an {@code hs-sub-<id>} thread whose top frame is
 * {@code VirtualThread.park} under {@code liveLoop} is parked with nothing
 * offered or past its unpark (read {@code pending=} beside it); one inside a
 * {@code Future.get}/queue take under a read executor is waiting on a read
 * (H2); an {@code hs-sub-read-<id>} or {@code hs-read-<n>} thread inside
 * {@code org.sqlite} native frames is the read in flight; a
 * {@code Thread.sleep} under the action executor is the run pipeline waiting
 * (H4); a pinned virtual thread is named by the JDK's own
 * {@code jdk.tracePinnedThreads} line in the same stdout (H1).</p>
 *
 * <p><strong>Wall clock.</strong> The dump file is named
 * {@code threads-<epochMillis>.txt} from {@code System.currentTimeMillis()} — a
 * file name, not a measured quantity: the one named exception to this module's
 * clock-injection convention in this class (the instruction's §8).</p>
 */
final class BusThreadDump {

    /** Threads shown by name, whatever their frames: HomeSynapse's own named threads. */
    static final String NAME_PREFIX = "hs-";
    /** Threads shown by frame: any thread with this package inside its top frames. */
    static final String FRAME_MARKER = "com.homesynapse";
    /** The frames considered for selection, and the frames printed per thread. */
    static final int FRAMES_SHOWN = 12;
    /** The bound on the {@code jcmd} process; past it the process is destroyed. */
    static final long JCMD_TIMEOUT_SECONDS = 10L;

    /** A plain-dump thread header: {@code #<tid> "<name>"}, then {@code virtual} for a virtual thread. */
    private static final Pattern HEADER = Pattern.compile("^#(\\d+) \"(.*)\"(.*)$");
    private static final String FRAME_INDENT = "      ";

    private BusThreadDump() {
    }

    /**
     * Dumps every thread of this JVM — virtual threads included when {@code jcmd}
     * attaches — into {@code dir} and renders the threads that matter.
     *
     * @param dir the directory for the dump file (the test's temp dir)
     * @return the rendering; never throws — every failure path returns a text that
     *         begins {@code bus.thread_dump:} and says why
     */
    static String capture(Path dir) {
        String reason;
        try {
            Path jcmd = jcmdExecutable();
            if (jcmd == null) {
                reason = "jcmd_absent java.home=" + System.getProperty("java.home");
            } else {
                Files.createDirectories(dir);
                Path file = dir.resolve("threads-" + System.currentTimeMillis() + ".txt");
                reason = runJcmd(jcmd, file);
                if (reason == null) {
                    return renderPlainDump(Files.readAllLines(file, StandardCharsets.UTF_8), file,
                            platformStates());
                }
            }
        } catch (IOException | RuntimeException failure) {
            reason = "dump_failed " + failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reason = "interrupted_awaiting_jcmd";
        }
        return platformOnly(reason);
    }

    /**
     * Renders a JDK 21 plain-format dump: the header line with the counts, then
     * every selected thread as one {@code bus.thread:} line and at most
     * {@value #FRAMES_SHOWN} {@code bus.thread_frame:} lines, in the dump's order.
     *
     * @param dumpLines      the dump file's lines
     * @param file           the dump file (named on the header line)
     * @param platformStates the live state per platform thread id; an id not in the
     *                       map (every virtual thread) reads {@code unknown}
     * @return the rendering
     */
    static String renderPlainDump(List<String> dumpLines, Path file,
            Map<Long, Thread.State> platformStates) {
        List<ThreadBlock> all = parsePlainDump(dumpLines);
        List<ThreadBlock> shown = select(all);
        return render("bus.thread_dump: threads=" + all.size() + " shown=" + shown.size()
                + " file=" + file, null, shown, platformStates);
    }

    /**
     * The fallback: {@code Thread.getAllStackTraces()} — platform threads only —
     * rendered the same way, with the reason on the second line.
     *
     * @param reason why the {@code jcmd} dump is not available
     * @return the rendering
     */
    static String platformOnly(String reason) {
        List<ThreadBlock> all = new ArrayList<>();
        Map<Long, Thread.State> states = new HashMap<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            List<String> frames = new ArrayList<>(entry.getValue().length);
            for (StackTraceElement frame : entry.getValue()) {
                frames.add(frame.toString());
            }
            all.add(new ThreadBlock(thread.threadId(), thread.getName(), thread.isVirtual(),
                    frames));
            states.put(thread.threadId(), thread.getState());
        }
        List<ThreadBlock> shown = select(all);
        return render("bus.thread_dump: source=platform_only threads=" + all.size()
                + " shown=" + shown.size(), "bus.thread_dump_fallback: reason=" + reason,
                shown, states);
    }

    // ── the pieces ───────────────────────────────────────────────────────────

    /** One thread of a dump: its id, name, virtual flag and frames, top first. */
    private record ThreadBlock(long tid, String name, boolean virtual, List<String> frames) {
    }

    /** {@code <java.home>/bin/jcmd(.exe)} — the JDK that runs this JVM, never {@code PATH}. */
    private static Path jcmdExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String candidate : List.of("jcmd.exe", "jcmd")) {
            Path path = bin.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Runs {@code jcmd <pid> Thread.dump_to_file -format=plain <file>} under the
     * process bound.
     *
     * @return {@code null} when the file was written, else the reason
     */
    private static String runJcmd(Path jcmd, Path file) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(jcmd.toString(),
                Long.toString(ProcessHandle.current().pid()),
                "Thread.dump_to_file", "-format=plain", file.toString())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(JCMD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "jcmd_timeout seconds=" + JCMD_TIMEOUT_SECONDS;
        }
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .strip().replace('\r', ' ').replace('\n', ' ');
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(file)) {
            return "jcmd_failed exit=" + process.exitValue() + " output=" + output;
        }
        return null;
    }

    private static Map<Long, Thread.State> platformStates() {
        Map<Long, Thread.State> states = new HashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            states.put(thread.threadId(), thread.getState());
        }
        return states;
    }

    /**
     * The plain format: a block starts at {@code #<tid> "<name>"}, its frames are
     * indented, the next header (or the end) closes it; the preamble (pid, time,
     * runtime version) precedes the first header and is skipped.
     */
    private static List<ThreadBlock> parsePlainDump(List<String> lines) {
        List<ThreadBlock> blocks = new ArrayList<>();
        long tid = -1L;
        String name = null;
        boolean virtual = false;
        List<String> frames = new ArrayList<>();
        for (String line : lines) {
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                if (name != null) {
                    blocks.add(new ThreadBlock(tid, name, virtual, List.copyOf(frames)));
                }
                tid = Long.parseLong(header.group(1));
                name = header.group(2);
                virtual = header.group(3).contains("virtual");
                frames = new ArrayList<>();
            } else if (name != null && line.startsWith(FRAME_INDENT)) {
                frames.add(line.strip());
            }
        }
        if (name != null) {
            blocks.add(new ThreadBlock(tid, name, virtual, List.copyOf(frames)));
        }
        return blocks;
    }

    private static List<ThreadBlock> select(List<ThreadBlock> all) {
        List<ThreadBlock> shown = new ArrayList<>();
        for (ThreadBlock block : all) {
            if (block.name().startsWith(NAME_PREFIX) || mentionsMarker(block.frames())) {
                shown.add(block);
            }
        }
        return shown;
    }

    private static boolean mentionsMarker(List<String> frames) {
        int limit = Math.min(FRAMES_SHOWN, frames.size());
        for (int i = 0; i < limit; i++) {
            if (frames.get(i).contains(FRAME_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static String render(String firstLine, String secondLine, List<ThreadBlock> shown,
            Map<Long, Thread.State> states) {
        StringBuilder out = new StringBuilder(128 + 1024 * shown.size());
        out.append(firstLine);
        if (secondLine != null) {
            out.append('\n').append(secondLine);
        }
        for (ThreadBlock block : shown) {
            Thread.State state = states.get(block.tid());
            out.append('\n').append("bus.thread: name=").append(block.name())
                    .append(" state=").append(state == null ? "unknown" : state.name())
                    .append(" virtual=").append(block.virtual())
                    .append(" tid=").append(block.tid());
            int limit = Math.min(FRAMES_SHOWN, block.frames().size());
            for (int i = 0; i < limit; i++) {
                out.append('\n').append("bus.thread_frame: ").append(block.frames().get(i));
            }
        }
        return out.toString();
    }
}
