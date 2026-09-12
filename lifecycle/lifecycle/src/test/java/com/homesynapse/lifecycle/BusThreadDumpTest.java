/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * FIX-2b-i (B) — the thread dump an await timeout leaves behind. One test, three
 * arms: (1) {@code capture} on the LIVE test JVM returns a text whose first line
 * starts {@code bus.thread_dump:} and which renders this test thread's own frame
 * — on the desk {@code jcmd} attaches and the file is written; a JVM without it
 * falls back to platform threads and the text says so; (2) the plain-dump
 * renderer pinned byte-exact on a synthetic JDK 21 dump — the selection rule (the
 * {@code hs-} names; the {@code com.homesynapse} marker inside the top 12 frames,
 * not past them), the 12-frame cap, the {@code state}/{@code virtual}/{@code tid}
 * keys; (3) the platform-only fallback on a forced reason. Nothing here touches
 * the bus; the render is pure.
 */
@DisplayName("BusThreadDump — capture on the live JVM renders the calling thread; the plain-dump grammar is pinned; the platform-only fallback names its reason (FIX-2b-i B)")
final class BusThreadDumpTest {

    /** Explicit no-arg constructor for {@code -Xlint:all -Werror} builds. */
    BusThreadDumpTest() {
    }

    @Test
    @DisplayName("capture: first line bus.thread_dump:, this thread's frame rendered, the file present unless source=platform_only; the synthetic dump renders exactly; the fallback carries its reason")
    void capture_onTheLiveTestJvm_rendersThisThread_andTheGrammarIsPinned(@TempDir Path tempDir) {
        // Arm 1 — the live JVM.
        String text = BusThreadDump.capture(tempDir);
        System.out.println(text);
        String firstLine = text.lines().findFirst().orElse("");
        assertThat(firstLine).startsWith("bus.thread_dump: ");
        assertThat(text).contains("bus.thread: name=")
                .contains("com.homesynapse.lifecycle.BusThreadDumpTest.capture_");
        if (firstLine.contains("source=platform_only")) {
            assertThat(text.lines().skip(1).findFirst().orElse(""))
                    .startsWith("bus.thread_dump_fallback: reason=");
        } else {
            assertThat(firstLine).matches("bus\\.thread_dump: threads=\\d+ shown=\\d+ file=.+");
            assertThat(Path.of(firstLine.substring(firstLine.indexOf(" file=") + 6)))
                    .isRegularFile();
        }

        // Arm 2 — the renderer on a synthetic JDK 21 plain dump (the desk's own
        // format, probed 2026-09-12): five threads, three shown.
        List<String> dump = List.of(
                "78760",
                "2026-09-12T02:32:41.469505500Z",
                "21.0.4+8-LTS-274",
                "",
                "#1 \"Test worker\"",
                "      java.base/java.lang.ProcessImpl.waitForInterruptibly(Native Method)",
                "      java.base/java.lang.ProcessImpl.waitFor(ProcessImpl.java:598)",
                "      com.homesynapse.lifecycle.BusThreadDump.runJcmd(BusThreadDump.java:150)",
                "      com.homesynapse.lifecycle.BusThreadDumpTest.capture(BusThreadDumpTest.java:40)",
                "",
                "#9 \"Reference Handler\"",
                "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)",
                "      java.base/java.lang.ref.Reference$ReferenceHandler.run(Reference.java:208)",
                "",
                "#40 \"hs-sub-automation_engine\" virtual",
                "      java.base/java.lang.VirtualThread.park(VirtualThread.java:596)",
                "      java.base/java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)",
                "      com.homesynapse.event.bus.InProcessEventBus.liveLoop(InProcessEventBus.java:536)",
                "      java.base/java.lang.VirtualThread.run(VirtualThread.java:329)",
                "",
                "#41 \"hs-write-0\"",
                "      f01", "      f02", "      f03", "      f04", "      f05", "      f06",
                "      f07", "      f08", "      f09", "      f10", "      f11", "      f12",
                "      f13", "      f14",
                "",
                "#50 \"marker-past-the-cap\"",
                "      g01", "      g02", "      g03", "      g04", "      g05", "      g06",
                "      g07", "      g08", "      g09", "      g10", "      g11", "      g12",
                "      com.homesynapse.lifecycle.Deep.thirteenth(Deep.java:1)",
                "");
        String rendered = BusThreadDump.renderPlainDump(dump, Path.of("threads-1.txt"),
                Map.of(1L, Thread.State.RUNNABLE, 41L, Thread.State.WAITING));
        assertThat(rendered).isEqualTo(String.join("\n",
                "bus.thread_dump: threads=5 shown=3 file=threads-1.txt",
                "bus.thread: name=Test worker state=RUNNABLE virtual=false tid=1",
                "bus.thread_frame: java.base/java.lang.ProcessImpl.waitForInterruptibly(Native Method)",
                "bus.thread_frame: java.base/java.lang.ProcessImpl.waitFor(ProcessImpl.java:598)",
                "bus.thread_frame: com.homesynapse.lifecycle.BusThreadDump.runJcmd(BusThreadDump.java:150)",
                "bus.thread_frame: com.homesynapse.lifecycle.BusThreadDumpTest.capture(BusThreadDumpTest.java:40)",
                "bus.thread: name=hs-sub-automation_engine state=unknown virtual=true tid=40",
                "bus.thread_frame: java.base/java.lang.VirtualThread.park(VirtualThread.java:596)",
                "bus.thread_frame: java.base/java.util.concurrent.locks.LockSupport.park(LockSupport.java:369)",
                "bus.thread_frame: com.homesynapse.event.bus.InProcessEventBus.liveLoop(InProcessEventBus.java:536)",
                "bus.thread_frame: java.base/java.lang.VirtualThread.run(VirtualThread.java:329)",
                "bus.thread: name=hs-write-0 state=WAITING virtual=false tid=41",
                "bus.thread_frame: f01", "bus.thread_frame: f02", "bus.thread_frame: f03",
                "bus.thread_frame: f04", "bus.thread_frame: f05", "bus.thread_frame: f06",
                "bus.thread_frame: f07", "bus.thread_frame: f08", "bus.thread_frame: f09",
                "bus.thread_frame: f10", "bus.thread_frame: f11", "bus.thread_frame: f12"));

        // Arm 3 — the platform-only fallback names its reason and still renders this thread.
        String fallback = BusThreadDump.platformOnly("forced_by_test");
        assertThat(fallback.lines().findFirst().orElse(""))
                .matches("bus\\.thread_dump: source=platform_only threads=\\d+ shown=\\d+");
        assertThat(fallback.lines().skip(1).findFirst().orElse(""))
                .isEqualTo("bus.thread_dump_fallback: reason=forced_by_test");
        assertThat(fallback).contains("com.homesynapse.lifecycle.BusThreadDumpTest.capture_");
    }
}
