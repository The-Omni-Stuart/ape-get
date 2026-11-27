// AppImage identity: the gate that decides whether an asset is fit to run at
// all, which image and which words an unpacked tree offers, and that every
// failure leaves an install with no icon and the repo's own name rather than a
// broken one.
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
static desktop_hint g_hint;   // what the last extraction told us

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

// The launcher's own entry, with the traps in it: a [Desktop Action …] group
// that repeats Name=, and a Name[de]= translation placed BEFORE the real key so
// a parser that accepts it cannot hide behind first-wins. Plus a GenericName and
// an X-AppImage-Version carrying a stale version, both of which we must not echo.
#define BRANDED_ENTRY \
    "[Desktop Entry]\n" \
    "Type=Application\n" \
    "Name[de]=DeckCraft Deutsch\n" \
    "Name=DeckCraft\n" \
    "GenericName=Presentation\n" \
    "Comment=Create and present slide decks\n" \
    "Exec=deckcraft %F\n" \
    "TryExec=deckcraft\n" \
    "Icon=ai.storyteller.deckcraft\n" \
    "StartupNotify=true\n" \
    "StartupWMClass=ai.storyteller.deckcraft\n" \
    "Categories=Office;Presentation;\n" \
    "Keywords=presentation;slides;\n" \
    "MimeType=application/x-deckcraft;\n" \
    "X-AppImage-Version=0.11.1\n" \
    "\n[Desktop Action new-window]\nName=New Window\nExec=deckcraft --new\n"

// the child half: pretend to be an AppImage runtime unpacking itself
static int as_appimage(void)
{
    const char *which = getenv("APEGET_ICON_STUB");
    if (which == NULL) which = "none";
    mkdir("squashfs-root", 0755);
    if (strcmp(which, "branded") == 0) {
        put("squashfs-root", "ai.storyteller.deckcraft.desktop", BRANDED_ENTRY);
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
    } else if (strcmp(which, "bad-notify") == 0) {
        put("squashfs-root", "tool.desktop",
            "[Desktop Entry]\nName=Maybe\nStartupNotify=maybe\nIcon=x\n");
        put("squashfs-root", "x.png", "PICKME");
    } else if (strcmp(which, "actions-only") == 0) {
        put("squashfs-root", "tool.desktop",
            "[Desktop Action new-window]\nName=New Window\nExec=tool --new\n");
        put("squashfs-root", ".DirIcon", "DIRICON");
    } else if (strcmp(which, "dupe-keys") == 0) {
        put("squashfs-root", "tool.desktop",
            "[Desktop Entry]\nName=Right\nName=Wrong\nIcon=x\n");
        put("squashfs-root", "x.png", "PICKME");
    } else if (strcmp(which, "two-entries") == 0) {
        // created in the wrong order on purpose: creation order must not decide
        // the label the user reads, the alphabet does
        put("squashfs-root", "z-first.desktop",
            "[Desktop Entry]\nName=Wrong\nIcon=first\n");
        put("squashfs-root", "first.png", "FIRST");
        put("squashfs-root", "a-second.desktop",
            "[Desktop Entry]\nName=Right\nIcon=second\n");
        put("squashfs-root", "second.png", "SECOND");
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

static char *slurp(const char *path)
{
    FILE *f = path != NULL ? fopen(path, "rb") : NULL;
    if (f == NULL) return NULL;
    char *body = malloc(65536);
    if (body == NULL) { fclose(f); return NULL; }
    size_t n = fread(body, 1, 65535, f);
    fclose(f);
    body[n] = '\0';
    return body;
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

// Stage an asset, ask it what it is, and report the icon and the words.
// NO_ICON demands no icon, ANY_ICON demands one of unknown name, NULL leaves the
// icon alone (a case about words says nothing about pictures).
#define NO_ICON   "<none>"
#define ANY_ICON  "<any>"

static char *read_asset(const char *root, const char *label, const char *stub,
    const char *rc, const char *name, mode_t mode, bool script,
    const char *want_rel, const char *want_body)
{
    char *app = path_fmt("%s/%s", root, label);
    if (app == NULL || !fs_mkdir_p(app)) { fail(label, "no app directory"); return NULL; }
    char *image = script ? stage_script(app, name) : stage_image(app, name, mode);
    if (image == NULL) { fail(label, "could not stage the asset"); return NULL; }
    if (stub != NULL) setenv("APEGET_ICON_STUB", stub, 1);
    else unsetenv("APEGET_ICON_STUB");
    if (rc != NULL) setenv("APEGET_ICON_RC", rc, 1);
    else unsetenv("APEGET_ICON_RC");
    desktop_hint_free(&g_hint);
    char *got = desktop_appimage_read(image, app, &g_hint);
    unsetenv("APEGET_ICON_STUB");

    char *want = want_rel != NULL && want_rel[0] != '<'
        ? path_fmt("%s/desktop/%s", app, want_rel) : NULL;
    if (want_rel == NULL) {  /* picture not asserted */ }
    else if (strcmp(want_rel, NO_ICON) == 0) {
        if (got != NULL) fail(label, "expected no icon, got %s", got);
    } else if (got == NULL) {
        fail(label, "expected an icon, got none");
    } else if (strcmp(want_rel, ANY_ICON) != 0
        && (want == NULL || strcmp(got, want) != 0)) {
        fail(label, "icon is %s, expected %s", got, want != NULL ? want : "?");
    } else {
        struct stat sb;
        if (stat(got, &sb) != 0 || (sb.st_mode & 0777) != 0644)
            fail(label, "icon %s is not 0644", got);
        if (want_body != NULL) {
            char *body = slurp(got);
            if (body == NULL) fail(label, "icon %s is not readable", got);
            else if (strcmp(body, want_body) != 0)
                fail(label, "icon holds %s, expected %s", body, want_body);
            free(body);
        }
    }
    int left = scratch_left(app);
    if (left != 0) fail(label, "%d scratch entries left behind", left);
    free(want);
    free(image);
    free(app);
    return got;
}

static void extract_case(const char *root, const char *label, const char *stub,
    const char *rc, const char *name, mode_t mode, bool script,
    const char *want_rel, const char *want_body)
{
    free(read_asset(root, label, stub, rc, name, mode, script, want_rel, want_body));
}

static void expect_field(const char *label, const char *what, const char *got,
    const char *want)
{
    if (want == NULL) {
        if (got != NULL) fail(label, "%s is %s, expected none", what, got);
    } else if (got == NULL) {
        fail(label, "%s absent, expected %s", what, want);
    } else if (strcmp(got, want) != 0) {
        fail(label, "%s is %s, expected %s", what, got, want);
    }
}

// What the app said about itself, verbatim where it is safe to repeat it
static void hint_case(const char *root, const char *label, const char *stub,
    const char *name, const char *comment, const char *categories,
    const char *keywords, const char *wm, const char *notify)
{
    extract_case(root, label, stub, NULL, "tool-1.0.0.AppImage", 0755, false,
        NULL, NULL);
    expect_field(label, "name", g_hint.name, name);
    expect_field(label, "comment", g_hint.comment, comment);
    expect_field(label, "categories", g_hint.categories, categories);
    expect_field(label, "keywords", g_hint.keywords, keywords);
    expect_field(label, "wm_class", g_hint.wm_class, wm);
    expect_field(label, "notify", g_hint.notify, notify);
}

// The file a launcher actually reads. The entry's name and Exec are what the
// caller passes (the command stays the lowercase slug); only the display
// metadata may come from the app.
static void entry_case(const char *root, const char *label, const char *stub,
    const char *want[], const char *avoid[])
{
    char *icon = read_asset(root, label, stub, NULL, "tool-1.0.0.AppImage",
        0755, false, NULL, NULL);
    char *app = path_fmt("%s/%s", root, label);
    char *entry = app != NULL ? path_fmt("%s/tool.desktop", app) : NULL;
    desktop_entry_write(entry, "tool", "/home/u/.local/bin/tool", "owner/tool",
        icon, &g_hint, false);
    char *body = slurp(entry);
    if (body == NULL) fail(label, "no entry was written");
    for (const char *const *w = want; body != NULL && *w != NULL; w++)
        if (strstr(body, *w) == NULL) fail(label, "entry lacks %s", *w);
    for (const char *const *a = avoid; body != NULL && *a != NULL; a++)
        if (strstr(body, *a) != NULL) fail(label, "entry carries %s", *a);
    free(body);
    free(entry);
    free(app);
    free(icon);
}

int main(void)
{
    if (getenv("APEGET_ICON_STUB") != NULL) return as_appimage();

    char tmpl[] = "/tmp/apeget-icon-XXXXXX";
    if (mkdtemp(tmpl) == NULL) return 1;
    const char *root = tmpl;
    const char *img = "tool-1.0.0.AppImage";

    // --- which icon -------------------------------------------------------
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
        NO_ICON, NULL);
    extract_case(root, "no-icons", "none", NULL, img, 0755, false, NO_ICON, NULL);
    extract_case(root, "runtime-fails", "none", "3", img, 0755, false,
        NO_ICON, NULL);
    // .appimage, AppImage, APPIMAGE: the suffix is a convention, not a case test
    extract_case(root, "lowercase-suffix", "branded", NULL, "tool.appimage",
        0755, false, "icon.png", "BRANDED");

    // the gate: an executable script is not a runtime, whatever it is named, and
    // this one would have handed over an icon if it had been run
    extract_case(root, "gate-renamed-script", NULL, NULL, img, 0755, true,
        NO_ICON, NULL);
    // wrong suffix: the asset is a fine runtime, and asking it is still not our
    // business, because nothing that ends otherwise is an AppImage
    extract_case(root, "gate-suffix", "branded", NULL, "tool-1.0.0.ap", 0755,
        false, NO_ICON, NULL);

    // --- which words ------------------------------------------------------
    hint_case(root, "hint-branded", "branded", "DeckCraft",
        "Create and present slide decks", "Office;Presentation;",
        "presentation;slides;", "ai.storyteller.deckcraft", "true");
    // an image that says nothing about itself leaves everything to our defaults
    hint_case(root, "hint-dironly", "dironly", NULL, NULL, NULL, NULL, NULL, NULL);
    // metadata survives even when there is no icon to be had
    hint_case(root, "hint-no-icon-but-name", "named-missing", "OpenCode",
        NULL, NULL, NULL, NULL, NULL);
    // StartupNotify takes true or false; anything else is not ours to repeat
    hint_case(root, "hint-bad-notify", "bad-notify", "Maybe", NULL, NULL, NULL,
        NULL, NULL);
    // keys outside [Desktop Entry] belong to menu items, not to the app
    hint_case(root, "hint-actions-only", "actions-only", NULL, NULL, NULL, NULL,
        NULL, NULL);
    // a file that says it twice: the first answer is the app's answer
    hint_case(root, "hint-dupe-keys", "dupe-keys", "Right", NULL, NULL, NULL,
        NULL, NULL);
    // two entries: the alphabet answers, not whatever readdir handed us first
    hint_case(root, "hint-two-entries", "two-entries", "Right", NULL, NULL, NULL,
        NULL, NULL);
    extract_case(root, "hint-two-entries-icon", "two-entries", NULL, img, 0755,
        false, "icon.png", "SECOND");

    // --- the entry a launcher reads ---------------------------------------
    const char *want_branded[] = {
        "Name=DeckCraft\n", "Comment=Create and present slide decks\n",
        "Categories=Office;Presentation;\n", "Keywords=presentation;slides;\n",
        "StartupWMClass=ai.storyteller.deckcraft\n", "StartupNotify=true\n",
        "Icon=", "Exec=\"/home/u/.local/bin/tool\"\n", "X-ApeGet-Managed=true\n",
        NULL
    };
    // what we deliberately do not echo: the action group's name, a translation,
    // a generic name, file associations, and a version baked into the entry
    const char *avoid_branded[] = {
        "New Window", "[de]", "GenericName", "MimeType", "X-AppImage-Version",
        "TryExec", "0.11.1", NULL
    };
    entry_case(root, "entry-branded", "branded", want_branded, avoid_branded);

    const char *want_plain[] = {
        "Name=tool\n", "Categories=Utility;\n", "Icon=",
        "Exec=\"/home/u/.local/bin/tool\"\n", NULL
    };
    // an app with nothing to say keeps the repo slug and the generic folder; its
    // .DirIcon is still an icon, so that line stays
    const char *avoid_keys[] = {
        "Keywords=", "StartupWMClass=", "StartupNotify=", "Comment=", NULL
    };
    entry_case(root, "entry-defaults", "dironly", want_plain, avoid_keys);

    // the name comes from the image, everything else falls back
    const char *want_partial[] = { "Name=OpenCode\n", "Categories=Utility;\n",
        "Icon=", NULL };
    entry_case(root, "entry-name-only", "named-missing", want_partial,
        avoid_keys);

    (void)fs_rm_tree(root);
    desktop_hint_free(&g_hint);
    if (failures != 0) {
        fprintf(stderr, "%d AppImage identity checks failed\n", failures);
        return 1;
    }
    puts("AppImage identity: branded name and menu metadata preferred, repo slug "
        "as fallback, foreign keys and unrunnable assets refused");
    return 0;
}
