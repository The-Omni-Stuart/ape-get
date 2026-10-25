package cz.cuni.mff.releasemanager.commands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.cuni.mff.releasemanager.cmd.CmdParser;
import cz.cuni.mff.releasemanager.install.UpdateChecker;
import cz.cuni.mff.releasemanager.provider.UpdateChannel;
import cz.cuni.mff.releasemanager.state.StateStore;

class OutdatedCommandTest {

    @Test
    void doesNotClaimEverythingIsUpToDateWhenACheckFailed() throws Exception {
        StateStore store = mock(StateStore.class);
        when(store.scanManifests()).thenReturn(List.of());
        UpdateChecker checker = mock(UpdateChecker.class);
        when(checker.check(any(), any()))
            .thenReturn(new UpdateChecker.Report(List.of(), true));
        CmdParser parser = mock(CmdParser.class);
        when(parser.getChannel()).thenReturn(null);

        OutdatedCommand command = new OutdatedCommand(
            parser, store, checker, UpdateChannel.STABLE);

        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok;
        try {
            System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
            ok = command.run();
        } finally {
            System.setOut(original);
        }
        String output = bytes.toString(StandardCharsets.UTF_8);

        assertFalse(ok);
        assertTrue(output.contains("Could not check every package"), output);
    }
}
