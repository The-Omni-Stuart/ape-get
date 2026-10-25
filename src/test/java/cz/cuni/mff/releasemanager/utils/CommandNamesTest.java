package cz.cuni.mff.releasemanager.utils;

import org.junit.jupiter.api.Test;

import cz.cuni.mff.releasemanager.types.Asset;
import cz.cuni.mff.releasemanager.types.RepoRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandNamesTest {

    @Test
    void derivesNameFromAssetBeforeVersionAndArchitecture() {
        assertEquals("keepassxc", CommandNames.fromAssetName(
            "KeePassXC-2.8.0-1-x86_64.AppImage"));
        assertEquals("nvim", CommandNames.fromAssetName(
            "nvim-linux-x86_64.appimage"));
        assertEquals("rg", CommandNames.fromAssetName(
            "rg-x86_64-unknown-linux-musl"));
        assertEquals("fastfetch", CommandNames.fromAssetName(
            "fastfetch-2.0-linux-amd64.com"));
        assertEquals("tool", CommandNames.fromAssetName(
            "tool-v1.2.3-windows-x64.exe"));
    }

    @Test
    void fallsBackToRepoName() {
        assertEquals("fastfetch", CommandNames.fromAsset(
            new RepoRef("github", "fastfetch-cli/fastfetch"),
            new Asset("url", "")));
    }

    @Test
    void validatesCommandNames() {
        assertThrows(IllegalArgumentException.class,
            () -> CommandNames.validate("../escape"));
        assertThrows(IllegalArgumentException.class,
            () -> CommandNames.validate("two words"));
        assertEquals("fastfetch", CommandNames.validate("fastfetch"));
        assertEquals("notepad++", CommandNames.validate("notepad++"));
    }
}
