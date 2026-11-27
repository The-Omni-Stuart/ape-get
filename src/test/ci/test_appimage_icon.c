// AppImage icons: the gate that decides whether an asset is fit to run at all,
// which image an unpacked tree offers, and that every failure leaves an install
// with no Icon= line rather than a broken one.
//
// The AppImage here is this test binary copied into place. It is a real ELF, so
// the gate admits it, which a text fixture never could be: reaching the icon
// means executing the asset, and the gate exists to refuse exactly that for
// anything that cannot be a runtime. Invoked with --appimage-extract it writes a
// canned tree instead of running the assertions, into the cwd it was given —
// which is also how the scratch directory is proven to be honoured.
// APEGET_ICON_STUB picks the tree, APEGET_ICON_RC the exit code.
#include "pipeline/internal.h"

#include "fs.h"
#include "path.h"

#include <dirent.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int failures;

static void fail(const char *label, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));

static void fail(const char *label, const char *fmt, ...)
{
    va_list ap;
    failures++;
    fprintf(stderr, "FAIL %s: ", label);
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static void put(const char *dir, const char *name, const char *body)
{
    char *p = path_fmt("%s/%s", dir, name);
    FILE *f = p != NULL ? fopen(p, "wb") : NULL;
    if (f != NULL) { fputs(body, f); fclose(f); }
    free(p);
}

// the child half: pretend to be an AppImage runtime unpacking itself
static int as_appimage(void)
{
    const char *which = getenv("APEGET_ICON_STUB");
    if (which == NULL) which = "none";
    mkdir("squashfs-root", 0755);
    if (strcmp(which, "branded") == 0) {
        put("squashfs-root", "ai.storyteller.deckcraft.desktop",
            "[Desktop Entry]\nType=Application\nName=DeckCraft\n"
            "Exec=deckcraft\nIcon=ai.storyteller.deckcraft\n");
        put("squashfs-root", "ai.storyteller.deckcraft.png", "BRANDED");
        put("squashfs-root", ".DirIcon", "DIRICON");
    } else if (strcmp(which, "dironly") == 0) {
        put("squashfs-root", ".DirIcon", "DIRICON");
        put("squashfs-root", "AppRun", "#!/bin/sh\n");
    } else if (strcmp(which, "named-missing") == 0) {
        put("squashfs-root", "opencode.desktop",
            "[Desktop Entry]\nType=Application\nName=OpenCode\nIcon=ai.opencode\n");
        put("squashfs-root", ".DirIcon", "DIRICON");
    } else if (strcmp(which, "wrong-ext") == 0) {
        put("squashfs-root", "opencode.desktop",
            "[Desktop Entry]\nType=Application\nName=OpenCode\nIcon=logo\n");
        put("squashfs-root", "logo.jpg", "NOTANICONFORMAT");
    }
    const char *rc = getenv("APEGET_ICON_RC");
    return rc != NULL ? atoi(rc) : 0;
}

// A copy of this binary standing in for an AppImage, at the given name and mode.
static char *stage_image(const char *dir, const char *name, mode_t mode)
{
    int in = open("/proc/self/exe", O_RDONLY);
    if (in < 0) return NULL;
    char *p = path_fmt("%s/%s", dir, name);
    int out = p != NULL ? open(p, O_WRONLY | O_CREAT | O_TRUNC, 0600) : -1;
    if (out < 0) { close(in); free(p); return NULL; }
    char buf[65536];
    bool ok = true;
    ssize_t n;
    while (ok && (n = read(in, buf, sizeof buf)) > 0)
        if (write(out, buf, (size_t)n) != n) ok = false;
    close(in);
    if (n < 0) ok = false;
    if (fchmod(out, mode) != 0) ok = false;
    close(out);
    if (!ok) { unlink(p); free(p); return NULL; }
    return p;
}

// An executable text script, big enough to clear the size floor, that writes a
// tree it would have won an icon with. This is the asset the gate exists for: a
// renamed script is runnable, so only the shape check keeps ape-get from
// executing an arbitrary file just to look at it — and unlike an ELF that was
// truncated or stripped of its execute bit, this one really would have run.
static char *stage_script(const char *dir, const char *name)
{
    char *p = path_fmt("%s/%s", dir, name);
    FILE *f = p != NULL ? fopen(p, "wb") : NULL;
    if (f == NULL) { free(p); return NULL; }
    fputs("#!/bin/sh\nmkdir -p squashfs-root\n"
        "printf '[Desktop Entry]\\nType=Application\\nName=tool\\nIcon=x\\n' "
        ">squashfs-root/tool.desktop\nprintf BRANDED >squashfs-root/x.png\n", f);
    for (int i = 0; i < 4096; i++) fputs("# padding to pass for a runtime\n", f);
    if (fclose(f) != 0) { unlink(p); free(p); return NULL; }
    if (chmod(p, 0755) != 0) { unlink(p); free(p); return NULL; }
    return p;
}

static bool icon_holds(const char *path, const char *want)
{
    FILE *f = path != NULL ? fopen(path, "rb") : NULL;
    if (f == NULL) return false;
    char body[64];
    size_t n = fread(body, 1, sizeof body - 1, f);
    fclose(f);
    body[n] = '\0';
    return strcmp(body, want) == 0;
}

// what the extraction left behind in app_dir: nothing, in every case
static int scratch_left(const char *app_dir)
{
    DIR *d = opendir(app_dir);
    if (d == NULL) return -1;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strncmp(e->d_name, "extract-", 8) == 0
            || strcmp(e->d_name, "squashfs-root") == 0) n++;
    }
    closedir(d);
    return n;
}

// Extract from a staged asset and check what landed under <app>/desktop.
// want_rel NULL means the install must have come out with no icon at all.
static void extract_case(const char *root, const char *label, const char *stub,
    const char *rc, const char *name, mode_t mode, bool script,
    const char *want_rel, const char *want_body)
{
    char *app = path_fmt("%s/%s", root, label);
    if (app == NULL || !fs_mkdir_p(app)) { fail(label, "no app directory"); return; }
    char *image = script ? stage_script(app, name)
        : stage_image(app, name, mode);
    if (image == NULL) { fail(label, "could not stage the asset"); return; }
    if (stub != NULL) setenv("APEGET_ICON_STUB", stub, 1);
    else unsetenv("APEGET_ICON_STUB");
    if (rc != NULL) setenv("APEGET_ICON_RC", rc, 1);
    else unsetenv("APEGET_ICON_RC");
    char *got = desktop_icon_from_appimage(image, app);
    unsetenv("APEGET_ICON_STUB");

    char *want = want_rel != NULL ? path_fmt("%s/desktop/%s", app, want_rel) : NULL;
    if (want_rel == NULL) {
        if (got != NULL) fail(label, "expected no icon, got %s", got);
    } else if (got == NULL) {
        fail(label, "expected an icon, got none");
    } else if (want == NULL || strcmp(got, want) != 0) {
        fail(label, "icon is %s, expected %s", got, want != NULL ? want : "?");
    } else if (!icon_holds(got, want_body)) {
        fail(label, "icon %s does not hold %s", got, want_body);
    } else {
        struct stat sb;
        if (stat(got, &sb) != 0 || (sb.st_mode & 0777) != 0644)
            fail(label, "icon %s is not 0644", got);
    }
    int left = scratch_left(app);
    if (left != 0) fail(label, "%d scratch entries left behind", left);
    free(want);
    free(got);
    free(image);
    free(app);
}

int main(void)
{
    if (getenv("APEGET_ICON_STUB") != NULL) return as_appimage();

    char tmpl[] = "/tmp/apeget-icon-XXXXXX";
    if (mkdtemp(tmpl) == NULL) return 1;
    const char *root = tmpl;
    const char *img = "tool-1.0.0.AppImage";

    // the icon the app's own entry names wins over the runtime's generic one
    extract_case(root, "branded", "branded", NULL, img, 0755, false,
        "icon.png", "BRANDED");
    // with no Icon= anywhere to follow, the runtime's own icon stands in
    extract_case(root, "dironly", "dironly", NULL, img, 0755, false,
        "icon.diricon", "DIRICON");
    // an entry naming an icon the tree does not contain is not a dead end
    extract_case(root, "named-missing", "named-missing", NULL, img, 0755, false,
        "icon.diricon", "DIRICON");
    // a desktop entry may not point at a jpeg, so that icon is not ours to take
    extract_case(root, "wrong-ext", "wrong-ext", NULL, img, 0755, false,
        NULL, NULL);
    extract_case(root, "no-icons", "none", NULL, img, 0755, false, NULL, NULL);
    extract_case(root, "runtime-fails", "none", "3", img, 0755, false,
        NULL, NULL);
    // .appimage, AppImage, APPIMAGE: the suffix is a convention, not a case test
    extract_case(root, "lowercase-suffix", "branded", NULL, "tool.appimage",
        0755, false, "icon.png", "BRANDED");

    // the gate: an executable script is not a runtime, whatever it is named, and
    // this one would have handed over an icon if it had been run
    extract_case(root, "gate-renamed-script", NULL, NULL, img, 0755, true,
        NULL, NULL);
    // wrong suffix: the asset is a fine runtime, and asking it is still not our
    // business, because nothing that ends otherwise is an AppImage
    extract_case(root, "gate-suffix", "branded", NULL, "tool-1.0.0.ap", 0755,
        false, NULL, NULL);

    (void)fs_rm_tree(root);
    if (failures != 0) {
        fprintf(stderr, "%d AppImage icon checks failed\n", failures);
        return 1;
    }
    puts("AppImage icons: branded icon preferred, .DirIcon fallback, an "
        "unrunnable or iconless asset gives up quietly and leaves nothing");
    return 0;
}
