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

// Locate the single "-from-<segment>" a delta name ends with (after the
// ".xz" and ".xdelta" envelopes were cut). The segment is a version:
// [A-Za-z0-9._+-]+; a second "-from-", path characters, whitespace or
// URL escapes make the name a plain unknown asset, never a delta.
static bool cut_from_segment(char *name, char *from, size_t cap)
{
    char *first = NULL, *last = NULL;
    for (char *p = name; (p = strstr(p, "-from-")) != NULL; p++)
        if (first == NULL) first = p; else last = p;
    if (first == NULL || last != NULL || first == name) return false;
    size_t sl = strlen(first + 6);
    if (sl == 0 || sl + 1 > cap) return false;
    for (const char *p = first + 6; *p != '\0'; p++) {
        char c = *p;
        bool ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9') || c == '.' || c == '_'
            || c == '+' || c == '-';
        if (!ok) return false;
    }
    snprintf(from, cap, "%s", first + 6);
    *first = '\0';
    return true;
}

// The source version of a delta asset name (".xz" transport envelope
// included), false for every other name or a malformed -from- segment.
bool artifact_delta_from(const char *name, char *from, size_t cap)
{
    if (name == NULL || cap == 0) return false;
    from[0] = '\0';
    char b[512];
    snprintf(b, sizeof b, "%s", name);
    size_t l = strlen(b);
    if (l > 3 && !strcasecmp(b + l - 3, ".xz")) b[l -= 3] = '\0';
    if (l < 7 || strcasecmp(b + l - 7, ".xdelta")) return false;
    b[l - 7] = '\0';
    return cut_from_segment(b, from, cap);
}

// Strip transport envelopes from an asset name: ".xz", then ".xdelta"
// plus its trailing "-from-<version>" segment. Returns the length
// written, 0 when nothing installable remains (malformed deltas).
size_t artifact_base_name(const char *name, char *out, size_t cap)
{
    if (name == NULL) { if (cap > 0) out[0] = '\0'; return 0; }
    snprintf(out, cap, "%s", name);
    size_t l = strlen(out);
    if (l > 3 && !strcasecmp(out + l - 3, ".xz")) out[l -= 3] = '\0';
    if (l >= 7 && !strcasecmp(out + l - 7, ".xdelta")) {
        out[l = l - 7] = '\0';
        char from[128];
        if (!cut_from_segment(out, from, sizeof from))
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
