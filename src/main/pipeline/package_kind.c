// Package-kind tables: name and option text <-> enum, per-platform support.

#include "pipeline/internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

const char *kind_manifest_name(int k)
{
    switch (k) {
    case K_APPIMAGE: return "appimage";
    case K_PORTABLE_EXE: return "portable-exe";
    case K_APE: return "ape";
    case K_MSI: return "msi";
    case K_EXE: return "exe";
    case K_DMG: return "dmg";
    default: return "unknown";
    }
}

// Strip transport envelopes from an asset name: ".xz", then (deltas,
// phase 2) ".xdelta" plus its trailing "-from-<version>" segment. Returns
// the length written, 0 when nothing installable remains.
size_t artifact_base_name(const char *name, char *out, size_t cap)
{
    if (name == NULL) { if (cap > 0) out[0] = '\0'; return 0; }
    snprintf(out, cap, "%s", name);
    size_t l = strlen(out);
    if (l > 3 && !strcasecmp(out + l - 3, ".xz")) out[l -= 3] = '\0';
    if (l >= 7 && !strcasecmp(out + l - 7, ".xdelta")) {
        out[l = l - 7] = '\0';
        char *from = NULL;
        for (char *p = out; (p = strstr(p, "-from-")) != NULL; p++)
            from = p;   // last occurrence wins
        if (from != NULL && from != out)
            *from = '\0';
        else
            out[0] = '\0';
    }
    return strlen(out);
}

int kind_from_asset_name(const char *name)
{
    char b[512];
    if (artifact_base_name(name, b, sizeof b) == 0) return K_UNKNOWN;
    name = b;
    if (ci_endswith(name, ".appimage")) return K_APPIMAGE;
    if (ci_endswith(name, ".msi")) return K_MSI;
    if (ci_endswith(name, ".dmg")) return K_DMG;
    if (ci_endswith(name, ".ape") || ci_endswith(name, ".com")) return K_APE;
    if (ci_endswith(name, ".exe")) return K_EXE;
    return K_UNKNOWN;
}

bool kind_is_portable(int k)
{
    return k == K_APPIMAGE || k == K_PORTABLE_EXE || k == K_APE;
}

// Linux supports appimage and ape; unknown never reaches the plan
bool kind_supported_on_linux(int k)
{
    return k == K_APPIMAGE || k == K_APE;
}

// -3 is unparseable --kind text
int kind_from_option(const char *v)
{
    if (blank(v) || !strcasecmp(v, "auto")) return K_UNKNOWN;
    if (!strcasecmp(v, "appimage")) return K_APPIMAGE;
    if (!strcasecmp(v, "ape")) return K_APE;
    if (!strcasecmp(v, "msi")) return K_MSI;
    if (!strcasecmp(v, "dmg")) return K_DMG;
    if (!strcasecmp(v, "exe") || !strcasecmp(v, "nsis")
        || !strcasecmp(v, "installer"))
        return K_EXE;
    if (!strcasecmp(v, "portable") || !strcasecmp(v, "portable-exe")
        || !strcasecmp(v, "portable_exe"))
        return K_PORTABLE_EXE;
    return -3;
}

// manifest kind text back to the enum; the update flow keeps the
// previously installed kind
int kind_from_manifest_kind(const char *v)
{
    if (v == NULL) return K_UNKNOWN;
    if (!strcasecmp(v, "appimage")) return K_APPIMAGE;
    if (!strcasecmp(v, "ape")) return K_APE;
    if (!strcasecmp(v, "msi")) return K_MSI;
    if (!strcasecmp(v, "dmg")) return K_DMG;
    if (!strcasecmp(v, "exe")) return K_EXE;
    if (!strcasecmp(v, "portable-exe")) return K_PORTABLE_EXE;
    return K_UNKNOWN;
}

bool kind_matches(int requested, int actual, bool allow_unknown)
{
    if (allow_unknown && actual == K_UNKNOWN) return true;
    switch (requested) {
    case K_APPIMAGE: case K_MSI: case K_DMG:
        return actual == requested;
    case K_PORTABLE_EXE: case K_EXE: return actual == K_EXE;
    case K_APE: return actual == K_APE || actual == K_EXE;
    default: return true;
    }
}

bool has_ape_magic(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (f == NULL) return false;
    char head[8];
    size_t n = fread(head, 1, 8, f);
    fclose(f);
    return n == 8 && (memcmp(head, "MZqFpD='", 8) == 0
        || memcmp(head, "jartsr='", 8) == 0);
}
