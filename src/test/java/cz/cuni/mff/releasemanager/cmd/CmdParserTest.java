package cz.cuni.mff.releasemanager.cmd;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class CmdParserTest {

    private CmdParser cmdParser;

    @BeforeEach
    void setUp() {
        cmdParser = new CmdParser();
    }

    @Test
    void testParseArgument() {
        String[] args = {"install", "argument"};
        Command result = cmdParser.parse(args);

        assertNotNull(result);
        assertEquals(Command.INSTALL, result);
        assertEquals("argument", result.argument);
    }

    @Test
    void testParseWithValidCommand() {
        String[] args = {"list"};
        Command result = cmdParser.parse(args);
        assertEquals(Command.LIST, result);
    }

    @Test
    void testParseCaseInsensitivityWithValidCommand() {
        String[] args = {"SEaRch", "argument"};
        Command result = cmdParser.parse(args);
        assertEquals(Command.SEARCH, result);
    }

    @Test
    void testParseWithInvalidCommand() {
        String[] args = {"INVALID_COMMAND"};
        Command result = cmdParser.parse(args);
        assertEquals(Command.USAGE, result);
    }

    @Test
    void testParseWithNoArguments() {
        String[] args = {};
        Command result = cmdParser.parse(args);
        assertEquals(Command.HELP, result);
    }

    @Test
    void testParseWithMissingRequiredArgument() {
        for (String command : new String[] {"search", "install", "uninstall", "update"}) {
            Command result = assertDoesNotThrow(() -> cmdParser.parse(new String[] {command}));
            assertEquals(Command.USAGE, result, command);
        }
    }

    @Test
    void testParseSearchJoinsMultipleWords() {
        Command result = cmdParser.parse(new String[] {"search", "two", "words"});

        assertEquals(Command.SEARCH, result);
        assertEquals("two words", result.argument);
    }

    @Test
    void testParseSearchKeepsQuotedPhrase() {
        Command result = cmdParser.parse(new String[] {"search", "two words"});

        assertEquals(Command.SEARCH, result);
        assertEquals("two words", result.argument);
    }

    @Test
    void testParseInstallRejectsExtraArguments() {
        for (String command : new String[] {"install", "uninstall", "update"}) {
            Command result = cmdParser.parse(new String[] {command, "owner/repo", "extra"});
            assertEquals(Command.USAGE, result, command);
        }
    }

    @Test
    void testParseListRejectsExtraArguments() {
        Command result = cmdParser.parse(new String[] {"list", "extra"});

        assertEquals(Command.USAGE, result);
    }

    @Test
    void testParseSearchRejectsBlankQuery() {
        for (String query : new String[] {"", "   "}) {
            Command result = cmdParser.parse(new String[] {"search", query});
            assertEquals(Command.USAGE, result, "[" + query + "]");
        }
    }
}