// list command: installed apps as a table or a JSON array.

#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

int list_run(const cmd_opts *o, state_ctx *st)
{
    if (o->outdated)
        return pipeline_outdated(o, st);
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_s(stderr, UI_ERROR, "Failed to load applications: state scan failed");
        return 1;
    }
    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"apps\":[");
        for (size_t k = 0; k < mc; k++) {
            manifest_entry *e = &ms[k];
            if (k) jb_lit(&b, ",");
            // member order: the original serialiser declared fields first
            jb_lit(&b, "{\"installed_at\":");
            json_null_or_string(&b, e->installed);
            bool first = false;
            jb_kv(&b, "updated_at", e->updated, &first);
            first = false;
            jb_kv(&b, "install_path", e->path, &first);
            first = false;
            jb_kv(&b, "provider", e->provider, &first);
            first = false;
            jb_kv(&b, "repo", e->repo, &first);
            first = false;
            jb_kv(&b, "version", e->version, &first);
            first = false;
            jb_kv(&b, "asset", e->asset, &first);
            first = false;
            jb_kv(&b, "kind", e->kind, &first);
            first = false;
            jb_kv(&b, "command", e->command, &first);
            if (e->locked) jb_lit(&b, ",\"locked\":true");
            jb_lit(&b, "}");
        }
        jb_lit(&b, "]}");
        puts(b.p);
        jb_free(&b);
    } else if (mc == 0) {
        put_out("No releases installed.");
    } else {
        for (size_t k = 0; k < mc; k++) {
            char compat_suf[64];
            compat_suf[0] = '\0';
            if (ms[k].compat != NULL)
                snprintf(compat_suf, sizeof(compat_suf), " (%s)",
                    ms[k].compat);
            char ref[1024], rest[1024];
            snprintf(ref, sizeof ref, "%s:%s", ms[k].provider, ms[k].repo);
            snprintf(rest, sizeof rest, " %s%s",
                ms[k].version != NULL ? ms[k].version : "unknown",
                compat_suf);
            ui_line_ref_tail(stdout, ref, rest,
                ms[k].locked ? " (locked)" : NULL, UI_WARN);
        }
    }
    manifest_list_free(ms, mc);
    return 0;
}
