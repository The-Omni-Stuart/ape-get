/* Linux desktop entries, the part of LinuxHandler that
   "is responsible for handling the installation and uninstallation of
   applications on Linux systems". */

#include "pipeline/internal.h"

#include "fs.h"
#include "path.h"
#include "proc.h"
#include "ui.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

// <data>/applications/<command>.desktop; data = $XDG_DATA_HOME, else the state
// root's parent; NULL when command is unset or invalid; dir not created here.
char *desktop_entry_path(const state_ctx *st, const char *command)
{
    if (command == NULL || *command == '\0' || !cn_valid(command))
        return NULL;
    const char *xdg = getenv("XDG_DATA_HOME");
    char *dir = NULL;
    if (xdg != NULL && *xdg != '\0') {
        dir = path_fmt("%s/applications", xdg);
    } else {
        char *slash = strrchr(st->root, '/');
        if (slash == NULL) return NULL;
        char *base = malloc((size_t)(slash - st->root) + 1);
        if (base == NULL) return NULL;
        memcpy(base, st->root, (size_t)(slash - st->root));
        base[slash - st->root] = '\0';
        dir = path_join(base, "applications");
        free(base);
    }
    if (dir == NULL) return NULL;
    char *r = path_fmt("%s/%s.desktop", dir, command);
    free(dir);
    return r;
}

// Copy --icon to <app>/desktop/icon<lower-ext> (0644, default extension when
// the name has no dot). Write errors degrade to no icon; install still works.
char *desktop_icon_copy(const char *requested, const char *app_dir)
{
    if (requested == NULL || *requested == '\0') return NULL;
    char *abs;
    if (requested[0] == '/') {
        abs = strdup(requested);
    } else {
        char cwd[4096];
        abs = getcwd(cwd, sizeof cwd) != NULL
            ? path_fmt("%s/%s", cwd, requested) : NULL;
    }
    if (abs == NULL || !fs_is_regular(abs)) {
        char *w = path_fmt("Warning: icon not found: %s",
            abs != NULL ? abs : requested);
        ui_msg(stderr, UI_WARN, "%s", w != NULL ? w : "icon");
        free(w);
        free(abs);
        return NULL;
    }
    const char *b = strrchr(abs, '/') != NULL ? strrchr(abs, '/') + 1 : abs;
    const char *dot = strrchr(b, '.');
    char ext[64];
    if (dot != NULL && strlen(dot) < sizeof ext) {
        size_t i = 0;
        for (; dot[i] != '\0'; i++)
            ext[i] = (char)tolower((unsigned char)dot[i]);
        ext[i] = '\0';
    } else {
        memcpy(ext, ".png", 5);
    }
    char *dir = path_join(app_dir, "desktop");
    char *to = dir != NULL ? path_fmt("%s/icon%s", dir, ext) : NULL;
    bool ok = dir != NULL && fs_mkdir_p(dir);
    free(dir);
    FILE *in = ok ? fopen(abs, "rb") : NULL;
    FILE *out = in != NULL && to != NULL ? fopen(to, "wb") : NULL;
    if (out != NULL) {
        char buf[8192];
        size_t n;
        while ((n = fread(buf, 1, sizeof buf, in)) > 0)
            if (fwrite(buf, 1, n, out) != n) { ok = false; break; }
        if (ferror(in)) ok = false;
    } else {
        ok = false;
    }
    if (in != NULL) fclose(in);
    if (out != NULL) {
        fclose(out);
        if (ok) chmod(to, 0644);
        else remove(to);
    }
    free(abs);
    if (!ok) { free(to); return NULL; }
    return to;
}

// Case-insensitive suffix test. Hand-rolled offsets from the end of a name are
// how the .desktop scan missed every entry for a whole debugging session.
static bool has_suffix_ci(const char *name, const char *suffix)
{
    size_t ln = strlen(name), ls = strlen(suffix);
    return ln >= ls && strcasecmp(name + ln - ls, suffix) == 0;
}

// The Icon= value of a .desktop file, trimmed; false if the key is absent.
static bool desktop_file_icon_key(const char *path, char *out, size_t cap)
{
    FILE *f = fopen(path, "r");
    if (f == NULL) return false;
    char line[1024];
    bool found = false;
    while (fgets(line, sizeof line, f) != NULL) {
        if (strncmp(line, "Icon=", 5) != 0) continue;
        char *v = line + 5;
        while (*v == ' ' || *v == '\t') v++;
        size_t n = strcspn(v, "\r\n");
        while (n > 0 && (v[n - 1] == ' ' || v[n - 1] == '\t')) n--;
        if (n == 0) continue;
        if (n >= cap) n = cap - 1;
        memcpy(out, v, n);
        out[n] = '\0';
        found = true;
    }
    fclose(f);
    return found;
}

// Which file in an unpacked AppImage represents it: the image its own top-level
// .desktop names in Icon=, else the runtime's .DirIcon. The Icon= value is a
// theme-style name with no extension, so it matches as a stem over the formats a
// desktop entry may point at. Only the top level is scanned: deeper icons live
// under usr/share/icons by size, and the choice there is a guess we can make
// wrong, while the top level is where the runtime keeps the one it advertises.
static char *pick_appimage_icon(const char *root)
{
    DIR *d = opendir(root);
    if (d == NULL) return NULL;
    char want[256];
    want[0] = '\0';
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (!has_suffix_ci(e->d_name, ".desktop")) continue;
        char *dp = path_fmt("%s/%s", root, e->d_name);
        bool got = dp != NULL && desktop_file_icon_key(dp, want, sizeof want);
        free(dp);
        if (got) break;
    }
    const char *stem = strrchr(want, '/') != NULL ? strrchr(want, '/') + 1 : want;
    size_t ls = strlen(stem);
    char *named = NULL, *fallback = NULL;
    rewinddir(d);
    while ((e = readdir(d)) != NULL) {
        if (named == NULL && ls > 0 && strncasecmp(e->d_name, stem, ls) == 0
            && e->d_name[ls] == '.'
            && (has_suffix_ci(e->d_name, ".png")
                || has_suffix_ci(e->d_name, ".svg")
                || has_suffix_ci(e->d_name, ".xpm")))
            named = path_fmt("%s/%s", root, e->d_name);
        if (fallback == NULL && strcmp(e->d_name, ".DirIcon") == 0)
            fallback = path_fmt("%s/%s", root, e->d_name);
        if (named != NULL && fallback != NULL) break;
    }
    closedir(d);
    if (named == NULL) return fallback;
    free(fallback);
    return named;
}

// An AppImage is an ELF binary with a payload appended, and every real one is
// megabytes. Checking that before running anything keeps us from executing a
// test fixture, a truncated download or a renamed text file just to ask it for
// an icon — the ask is decorative, so the preconditions for making it are not.
static bool looks_like_appimage(const char *image)
{
    struct stat sb;
    if (image == NULL || stat(image, &sb) != 0 || !S_ISREG(sb.st_mode))
        return false;
    if (sb.st_size < 65536 || access(image, X_OK) != 0) return false;
    unsigned char magic[4];
    FILE *f = fopen(image, "rb");
    bool elf = f != NULL && fread(magic, 1, 4, f) == 4
        && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L'
        && magic[3] == 'F';
    if (f != NULL) fclose(f);
    return elf;
}

// The AppImage runtime unpacks into <cwd>/squashfs-root, so this runs in a
// scratch directory and asks for nothing outside it. Payloads are large
// (measured: 162 MB out of a 77 MB image in 0.23 s, 211 MB out of 135 MB in
// 0.64 s, peak RSS under 14 MB), which is why the scratch dir sits next to the
// app rather than in /tmp: /tmp is tmpfs on the machines we ship to, so a big
// image would be paid for in RAM. The deadline is short on purpose — an icon
// is decoration, and a runtime whose helper will not exit must not stall the
// install that only wanted to look at it. A runtime that detaches a helper
// also escapes the group kill, so this is the real bound, not a courtesy.
// Nothing here is fatal: an install that loses an icon still installs.
char *desktop_icon_from_appimage(const char *image, const char *app_dir)
{
    if (image == NULL || app_dir == NULL) return NULL;
    if (!has_suffix_ci(image, ".AppImage")) return NULL;
    if (!looks_like_appimage(image)) return NULL;

    char *tmpl = path_fmt("%s/extract-XXXXXX", app_dir);
    char *dir = tmpl != NULL ? mkdtemp(tmpl) : NULL;
    if (dir == NULL) { free(tmpl); return NULL; }
    char *root = path_fmt("%s/squashfs-root", dir);
    const char *av[] = { image, "--appimage-extract", NULL };
    (void)proc_run_env(av, NULL, dir, 25000, NULL);

    char *picked = pick_appimage_icon(root);
    char *copied = picked != NULL ? desktop_icon_copy(picked, app_dir) : NULL;
    // no icon is a fine outcome and says nothing: the entry renders, the
    // install succeeded, and every AppImage whose runtime declines would
    // otherwise read this line on every install
    (void)fs_rm_tree(dir);
    free(picked);
    free(root);
    free(tmpl);
    return copied;
}

// generic desktop entry, plus the C-only X-ApeGet-Managed key
void desktop_entry_write(const char *entry, const char *command,
    const char *launcher, const char *repo_path, const char *icon,
    bool terminal)
{
    if (entry == NULL) return;
    const char *slash = strrchr(repo_path, '/');
    const char *name = slash != NULL ? slash + 1 : repo_path;
    if (name == NULL) name = command;
    char *parent = strdup(entry);
    if (parent != NULL) {
        char *p = strrchr(parent, '/');
        if (p != NULL) { *p = '\0'; fs_mkdir_p(parent); }
        free(parent);
    }
    FILE *f = fopen(entry, "w");
    if (f == NULL) return;
    fprintf(f, "[Desktop Entry]\nType=Application\nName=%s\n"
        "Exec=\"%s\"\nTerminal=%s\nCategories=Utility;\n",
        name, launcher, terminal ? "true" : "false");
    if (icon != NULL) fprintf(f, "Icon=%s\n", icon);
    fputs("X-ApeGet-Managed=true\n", f);
    fclose(f);
    chmod(entry, 0644);
}

// The entry to record: --no-desktop takes it out; --desktop and AppImage
// installs (default_on) put it in; other kinds inherit only a file already
// carrying our managed marker. An existing file without the marker is
// foreign — never claimed, never overwritten, whatever was asked for (P10).
const char *desktop_recorded(const cmd_opts *o, const char *entry,
    bool default_on)
{
    if (entry == NULL || o->no_desktop) return NULL;
    if (desktop_entry_owned(entry)) return entry;
    if (!o->desktop && !default_on) return NULL;
    if (path_exists(entry)) {
        char *w = path_fmt("Desktop entry: not overwriting foreign file at %s",
            entry);
        ui_msg(stderr, UI_WARN, "%s", w != NULL ? w : "desktop entry");
        free(w);
        return NULL;
    }
    return entry;
}

// Files ape-get writes carry the X-ApeGet-Managed key; nothing without it
// may be deleted as one of ours, whatever a manifest once recorded.
bool desktop_entry_owned(const char *entry)
{
    size_t n = 0;
    char *text = entry != NULL ? fs_read_file(entry, &n) : NULL;
    bool ok = text != NULL && strstr(text, "X-ApeGet-Managed=true") != NULL;
    free(text);
    return ok;
}
