package cz.cuni.mff.releasemanager.cmd;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class ConsolePromptTest {

    @Test
    void acceptsYesAndY() {
        assertTrue(new ConsolePrompt(() -> "y", true).confirm("question", false));
        assertTrue(new ConsolePrompt(() -> "YES", true).confirm("question", false));
        assertFalse(new ConsolePrompt(() -> "n", true).confirm("question", false));
        assertFalse(new ConsolePrompt(() -> "", true).confirm("question", false));
    }

    @Test
    void assumeYesSkipsInput() {
        assertTrue(new ConsolePrompt(() -> {
            throw new IllegalStateException("input should not be read");
        }, false).confirm("question", true));
    }

    @Test
    void refusesWithoutInteractiveTerminal() {
        assertFalse(new ConsolePrompt(() -> "y", false).confirm("question", false));
    }

    @Test
    void refusesOnEndOfInput() {
        assertFalse(new ConsolePrompt(() -> {
            throw new NoSuchElementException("eof");
        }, true).confirm("question", false));
    }
}
