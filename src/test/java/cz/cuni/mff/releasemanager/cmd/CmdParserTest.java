package cz.cuni.mff.releasemanager.cmd;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void testParseStrictFlagBeforeCommand() {
        Command result = cmdParser.parse(new String[] {"--strict", "install", "owner/repo"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isStrict());
    }

    @Test
    void testParseStrictFlagAfterArgument() {
        Command result = cmdParser.parse(new String[] {"install", "owner/repo", "--strict"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isStrict());
    }

    @Test
    void testParseWithoutStrictFlag() {
        cmdParser.parse(new String[] {"list"});

        assertFalse(cmdParser.isStrict());
    }

    @Test
    void testParseAssumeYesBeforeCommand() {
        Command result = cmdParser.parse(new String[] {"--yes", "install", "owner/repo"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isAssumeYes());
    }

    @Test
    void testParseAssumeYesAfterArgument() {
        Command result = cmdParser.parse(new String[] {"install", "owner/repo", "--yes"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isAssumeYes());
    }

    @Test
    void testParseRequireSignatureBeforeCommand() {
        Command result = cmdParser.parse(
            new String[] {"--require-signature", "install", "owner/repo"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isRequireSignature());
    }

    @Test
    void testParseRequireSignatureAfterArgument() {
        Command result = cmdParser.parse(
            new String[] {"install", "owner/repo", "--require-signature"});

        assertEquals(Command.INSTALL, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isRequireSignature());
    }

    @Test
    void testParseWithoutRequireSignature() {
        cmdParser.parse(new String[] {"list"});

        assertFalse(cmdParser.isRequireSignature());
    }

    @Test
    void testParseAllowSignerChangeBeforeCommand() {
        Command result = cmdParser.parse(
            new String[] {"--allow-signer-change", "update", "owner/repo"});

        assertEquals(Command.UPDATE, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isAllowSignerChange());
    }

    @Test
    void testParseAllowSignerChangeAfterArgument() {
        Command result = cmdParser.parse(
            new String[] {"update", "owner/repo", "--allow-signer-change"});

        assertEquals(Command.UPDATE, result);
        assertEquals("owner/repo", result.argument);
        assertTrue(cmdParser.isAllowSignerChange());
    }

    @Test
    void testParseWithoutAllowSignerChange() {
        cmdParser.parse(new String[] {"list"});

        assertFalse(cmdParser.isAllowSignerChange());
    }

    @Test
    void testParseWithoutAssumeYes() {
        cmdParser.parse(new String[] {"list"});

        assertFalse(cmdParser.isAssumeYes());
    }
}