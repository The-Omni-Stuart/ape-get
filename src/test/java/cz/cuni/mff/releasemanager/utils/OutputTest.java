package cz.cuni.mff.releasemanager.utils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputTest {

    @AfterEach
    void reset() {
        Output.configure(false, false);
    }

    @Test
    void dataGoesToStdoutAndDiagnosticsGoToStderr() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Output.configure(false, false);

            Output.data("result");
            Output.info("progress");
            Output.error("problem");
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }

        String nl = System.lineSeparator();
        assertEquals("result" + nl, out.toString(StandardCharsets.UTF_8));
        assertEquals("progress" + nl + "problem" + nl,
            err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void infoIsHiddenForQuietAndJson() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            Output.configure(true, false);
            Output.info("quiet");
            Output.configure(false, true);
            Output.info("json");
        } finally {
            System.setErr(oldErr);
        }

        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void jsonWritesOneDocumentToStdout() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            Output.configure(false, true);
            Output.json(java.util.Map.of("installed", true));
        } finally {
            System.setOut(oldOut);
        }

        assertEquals("{\"installed\":true}" + System.lineSeparator(),
            out.toString(StandardCharsets.UTF_8));
    }
}
