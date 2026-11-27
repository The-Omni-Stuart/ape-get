// PATH registration: the one line setup writes so a shell started later finds
// the command it just installed. Only lines setup wrote are ever removed;
// everything the user put in the file stays where it is.

#include "platform.h"

#include "fs.h"
#include "path.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Marks a line as setup's, so a re-run can pick it back out of a file setup
// did not write. One line is the whole of what puts a directory in front of
// PATH.
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

// Every line of text that setup did not write, one per line; NULL only when
// the allocation fails.
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
            w += len;
            out[w++] = '\n';
        }
        p += len + (e != NULL ? 1 : 0);
    }
    out[w] = '\0';
    return out;
}

char *platform_path_target(platform_os os)
{
    const char *file = os == OS_MACOS ? ".zshrc"
        : os == OS_LINUX ? ".profile" : NULL;
    if (file == NULL) return NULL;
    char *home = path_home();
    if (home == NULL) return NULL;
    char *target = path_join(home, file);
    free(home);
    return target;
}

// Whether this process's PATH already holds dir as one of its entries. A
// shell that can already find the command needs no line written for it, and
// ~/.local/bin is on PATH out of the box on the systems setup runs on.
bool platform_path_effective(const char *dir)
{
    const char *path = getenv("PATH");
    if (path == NULL || dir == NULL) return false;
    size_t dl = strlen(dir);
    if (dl == 0) return false;
    for (const char *p = path; ; ) {
        const char *e = strchr(p, ':');
        size_t len = e != NULL ? (size_t)(e - p) : strlen(p);
        if (len == dl && !strncmp(p, dir, len)) return true;
        if (e == NULL) return false;
        p = e + 1;
    }
}

bool platform_path_registered(platform_os os, const char *dir)
{
    char *file = platform_path_target(os);
    char *want = dir != NULL ? path_line(dir) : NULL;
    char *text = file != NULL && want != NULL && fs_exists(file)
        ? fs_read_file(file, NULL) : NULL;
    bool hit = false;
    for (const char *p = text != NULL ? text : ""; !hit && *p != '\0'; ) {
        const char *e = strchr(p, '\n');
        size_t len = e != NULL ? (size_t)(e - p) : strlen(p);
        hit = len == strlen(want) && !memcmp(p, want, len);
        p += len + (e != NULL ? 1 : 0);
    }
    free(text);
    free(file);
    free(want);
    return hit;
}

bool platform_path_register(platform_os os, const char *dir,
    char *err, size_t cap)
{
    if (os != OS_LINUX && os != OS_MACOS) {
        snprintf(err, cap, "no PATH file is written for %s here",
            platform_os_name(os));
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
