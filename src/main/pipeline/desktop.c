/* Linux desktop entries, the part of LinuxHandler that
   "is responsible for handling the installation and uninstallation of
   applications on Linux systems". */

#include "pipeline/internal.h"

#include "fs.h"
#include "path.h"
#include "ui.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

// generic desktop entry (AppImage kinds carry embedded metadata
// instead), plus the C-only X-ApeGet-Managed key
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

// The entry to record: --no-desktop takes it out, --desktop puts it in
// whatever the old state said; an entry left by an earlier run stays recorded.
const char *desktop_recorded(const cmd_opts *o, const char *entry)
{
    if (entry == NULL || o->no_desktop) return NULL;
    return o->desktop || fs_exists(entry) ? entry : NULL;
}
