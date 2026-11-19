// PATH registration: the entry setup writes so that a shell opened later
// finds the command it just installed. Only setup's own entry is ever
// touched; whatever the user put in the file is left where it is.

#include "platform.h"

#include "fs.h"
#include "path.h"
#include "platform/internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

// The suffix marking a line as setup's, so a re-run can pick it back out of
// a file it did not create. One line, because that is the whole of what is
// needed to put a directory in front of PATH.
#define OUR_TAG "  # ape-get"

static char *path_line(const char *dir)
{
    return path_fmt("export PATH=\"%s:$PATH\"%s", dir, OUR_TAG);
}

static bool is_ours(const char *line, size_t len)
{
    static const size_t taglen = sizeof OUR_TAG - 1;
    return len >= taglen
        && memcmp(line + len - taglen, OUR_TAG, taglen) == 0;
}

// Every line of `text` that setup did not write, newline-normalised.
static char *strip_ours(const char *text)
{
    if (text == NULL) return strdup("");
    char *out = malloc(strlen(text) + 1);
    if (out == NULL) return NULL;
    size_t w = 0;
    const char *p = text;
    while (*p != '\0') {
        const char *e = strchr(p, '\n');
        size_t len = e != NULL ? (size_t)(e - p) : strlen(p);
        if (!is_ours(p, len)) {
            memcpy(out + w, p, len);
            out[w + len] = '\n';
            w += len + 1;
        }
        p += len + (e != NULL ? 1 : 0);
    }
    out[w] = '\0';
    return out;
}

// ---- Windows: the user's Path value in the registry --------------------
// Read and written through the NT layer, in the two calls in winreg.c, rather
// than by asking reg.exe: no child process can be started on Windows by this
// program at all. What this file is responsible for is what happens to the
// value once it is text - whether the directory is already in it, and where
// ours goes when it is not.

// Does one `;`-separated entry of the stored value name this directory?
// Windows paths ignore case, and a trailing separator names the same place.
static bool entry_is_dir(const char *entry, size_t len, const char *dir)
{
    while (len > 0 && (entry[len - 1] == '\\' || entry[len - 1] == '/'))
        len--;
    size_t dl = strlen(dir);
    while (dl > 0 && (dir[dl - 1] == '\\' || dir[dl - 1] == '/')) dl--;
    return len == dl && !strncasecmp(entry, dir, dl);
}

static bool value_lists(const char *value, const char *dir)
{
    for (const char *p = value; *p != '\0'; ) {
        const char *s = strchr(p, ';');
        size_t len = s != NULL ? (size_t)(s - p) : strlen(p);
        if (entry_is_dir(p, len, dir)) return true;
        if (s == NULL) break;
        p = s + 1;
    }
    return false;
}

// The current Path value as text, NULL when the registry holds no such value.
static char *reg_path_value(void)
{
    char16_t *wide = platform_win_reg_read();
    if (wide == NULL) return NULL;
    char *value = platform_utf8_of_utf16(wide);
    free(wide);
    return value;
}

// The registry belongs to the machine running this rather than to the layout
// setup has been asked to write, so on a system that is not Windows - which
// setup reaches by being told - there is nothing here to read or write. The two
// registry calls refuse without this question being put to them; it is asked
// here so that the answer can say why.
static bool reg_here(char *err, size_t cap)
{
    if (platform_current() == OS_WINDOWS) return true;
    snprintf(err, cap, "this system has no Windows registry to write");
    return false;
}

static bool reg_add(const char *dir, char *err, size_t cap)
{
    if (!reg_here(err, cap)) return false;
    char *current = reg_path_value();
    size_t cl = current != NULL ? strlen(current) : 0;
    while (cl > 0 && current[cl - 1] == ';') cl--;   // empty trailing entry
    char *value = cl > 0 ? path_fmt("%.*s;%s", (int)cl, current, dir)
                         : strdup(dir);
    char16_t *wide = value != NULL ? platform_utf16_of_utf8(value) : NULL;
    bool ok = wide != NULL && platform_win_reg_write(wide);
    if (!ok) {
        snprintf(err, cap, wide == NULL
            ? "cannot build the new PATH value"
            : "the registry refused the change");
    }
    free(current);
    free(value);
    free(wide);
    return ok;
}

// ---- the question and the write ----------------------------------------

bool platform_path_registered(platform_os os, const char *dir)
{
    if (os == OS_WINDOWS) {
        char *value = reg_path_value();
        if (value == NULL) return false;
        bool hit = value_lists(value, dir);
        free(value);
        return hit;
    }
    char *file = platform_path_target(os);
    if (file == NULL) return false;
    char *text = fs_read_file(file, NULL);
    free(file);
    if (text == NULL) return false;
    char *want = path_line(dir);
    bool hit = false;
    for (const char *p = text; *p != '\0' && !hit; ) {
        const char *e = strchr(p, '\n');
        size_t len = e != NULL ? (size_t)(e - p) : strlen(p);
        if (want != NULL && len == strlen(want) && !memcmp(p, want, len))
            hit = true;
        p += len + (e != NULL ? 1 : 0);
    }
    free(want);
    free(text);
    return hit;
}

bool platform_path_register(platform_os os, const char *dir,
    char *err, size_t cap)
{
    if (os == OS_WINDOWS) return reg_add(dir, err, cap);
    if (os != OS_LINUX && os != OS_MACOS) {
        snprintf(err, cap, "this platform's PATH is not written here");
        return false;
    }
    char *file = platform_path_target(os);
    if (file == NULL) {
        snprintf(err, cap, "cannot name the file to write");
        return false;
    }
    char *text = NULL;
    if (fs_exists(file)) {
        text = fs_read_file(file, NULL);
        if (text == NULL) {   // present but unreadable: never clobber it
            snprintf(err, cap, "cannot read %s", file);
            free(file);
            return false;
        }
    }
    char *kept = strip_ours(text);
    char *line = path_line(dir);
    char *out = kept != NULL && line != NULL
        ? path_fmt("%s%s\n", kept, line) : NULL;
    bool ok = out != NULL && fs_write_file(file, out);
    if (!ok) snprintf(err, cap, "cannot write %s", file);
    free(text);
    free(kept);
    free(line);
    free(out);
    free(file);
    return ok;
}
