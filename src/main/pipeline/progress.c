// Download progress: one live ASCII line, throttled to ~1 s by ui_progress;
// rate is a moving average.

#include "pipeline/internal.h"

#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <time.h>

char dl_line_buf[320];

static void dl_bytes(char *b, size_t cap, double v)
{
    if (v >= 1048576.0) snprintf(b, cap, "%.1f MB", v / 1048576.0);
    else if (v >= 1024.0) snprintf(b, cap, "%.0f KB", v / 1024.0);
    else snprintf(b, cap, "%.0f B", v);
}

void dl_progress(size_t done, long long total, void *ud)
{
    dl_ctx *d = ud;
    time_t now = time(NULL);
    if (d->bprev != 0 && now > d->tprev && done > d->bprev) {
        double inst = (double)(done - d->bprev) / (double)(now - d->tprev);
        d->ewma = d->ewma > 0.0 ? 0.7 * d->ewma + 0.3 * inst : inst;
        d->tprev = now;
        d->bprev = done;
    } else if (d->bprev == 0) {
        d->tprev = now;
        d->bprev = done;
    }
    char cur[32];
    dl_bytes(cur, sizeof cur, (double)done);
    if (total > 0) {
        int pct = (int)((double)done * 100.0 / (double)total);
        if (pct > 100) pct = 100;
        if ((long long)done >= total) pct = 100;
        char bar[24];
        int cells = pct * 20 / 100;
        for (int i = 0; i < 20; i++) bar[i] = i < cells ? '#' : '-';
        bar[20] = '\0';
        char tot[32], rt[40];
        dl_bytes(tot, sizeof tot, (double)total);
        if (d->ewma > 0.0) {
            char rb[32];
            dl_bytes(rb, sizeof rb, d->ewma);
            snprintf(rt, sizeof rt, "%s/s", rb);
        } else snprintf(rt, sizeof rt, "--");
        snprintf(d->line, 320, "%s [%s] %d%% %s - %s/%s",
            d->asset, bar, pct, rt, cur, tot);
    } else {
        char bar[21];
        int ph = (int)((done / 16384) % 14);
        for (int i = 0; i < 17; i++) bar[i] = '-';
        bar[17] = '\0';
        bar[ph] = bar[ph + 1] = bar[ph + 2] = '>';
        snprintf(d->line, 320, "%s [%s] %s", d->asset, bar, cur);
    }
    // the 100% frame must always render: throttling cannot swallow it
    ui_progress(d->line, total > 0 && (long long)done >= total);
}
