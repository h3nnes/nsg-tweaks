/*
 * nsgmod-diagd: NSG Tweaks DIAG helper.
 *
 * Root daemon that subscribes to NR5G diag logs through the diag-router Diag
 * Consumer Interface (vendor libdiag.so) and pushes per-record NDJSON lines to
 * a single connected client over an abstract unix socket.
 *
 * DCI only: never takes the on-device (MD) logging session, never touches the
 * main log masks and never switches logging modes, so NSG's own diag session
 * and USB/QXDM diag keep working. Read-only towards the radio.
 *
 * Wire format of DCI log callbacks:
 *   [u16 len][u16 code][u64 timestamp][payload]
 * NR payload headers start with a version word: minor (LE16), major (LE16);
 * layout tables are keyed by (major << 16) | minor.
 *
 * Output records (one JSON object per line):
 *   {"c":"snr","ts":T,"cc":N,"rs":R,"db":F}   serving SNR, R=0 SSB / 1 TRS(CSI)
 *   {"c":"cfg","ts":T,"cells":[{...},...]}    RRC serving cell configuration
 *   {"c":"pcell","ts":T,"pci":N,"dl":N}       RRC PCell identity
 *   {"c":"csf","ts":T,"cc":N,"ri":N,"cqi":N}  MAC CSF rank/CQI report
 * T = raw modem diag timestamp of the log record.
 */
#include <android/dlext.h>
#include <dlfcn.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include "rrc_csi.h"        /* CSI-RS ports from B821 RRC reconfigurations (GPL-3,
                             * vendored from qcom-band-menu-diag-dci) */

#define LIBDIAG       "/vendor/lib64/libdiag.so"
#define SOCKET_NAME   "nsgmod_diagd"
#define DIAG_PROC_MSM 0
#define DIAG_CON_MPSS (1 << 1)
#define DCI_NO_ERROR  1001

#define STARTUP_GRACE_MS    8000

static const uint16_t CODES[] = { 0xB8DD, 0xB8D8, 0xB825, 0xB823, 0xB8A7, 0xB97F, 0xB887, 0xB821 };
#define NCODES ((int)(sizeof(CODES) / sizeof(CODES[0])))

/* Raw B821 sample dump for offline UPER decoder development: the first
 * records received are written as one hex line each to -dump <file>. */
#define B821_DUMP_MAX 200
static const char *b821_dump_path = NULL;
/* B887 ground-truth records go to <dump>.b887 (separate file so the two
 * dumpers cannot truncate each other). */
static char b887_dump_buf[512];
static const char *b887_dump_path = NULL;
static int b821_dump_count = 0;
static int b887_dump_count = 0;

typedef int (*lsm_init_t)(void *);
typedef int (*reg_client_t)(int *, uint16_t *, int, void *);
typedef int (*reg_stream_t)(int, void (*)(unsigned char *, int),
                            void (*)(unsigned char *, int));
typedef int (*log_cfg_t)(int, int, uint16_t *, int);
typedef int (*release_client_t)(int);

static lsm_init_t       Diag_LSM_Init;
static reg_client_t     diag_register_dci_client;
static reg_stream_t     diag_register_dci_stream_proc;
static log_cfg_t        diag_log_stream_config;
static release_client_t diag_release_dci_client;

static volatile sig_atomic_t stopping = 0;
static int  client_handle = -1;
static int  out_fd        = -1;
static long client_uid    = -1;

/* ------------------------------------------------------------------ */
/* Bit extraction                                                      */
/* ------------------------------------------------------------------ */

struct pos { uint16_t off; uint8_t bits; };

static uint32_t bits(const uint8_t *p, uint16_t off, uint8_t n)
{
    uint32_t v = 0;
    for (uint8_t i = 0; i < n; i++) {
        uint16_t b = off + i;
        v |= (uint32_t)((p[b >> 3] >> (b & 7)) & 1) << i;
    }
    return v;
}

static uint32_t at(const uint8_t *p, struct pos f) { return f.bits ? bits(p, f.off, f.bits) : 0; }

static int fits(struct pos f, int n) { return f.bits && (f.off + f.bits + 7) / 8 <= n; }

static uint32_t nr_ver(const uint8_t *p)
{
    return (uint32_t)(p[2] | (p[3] << 8)) << 16 | (uint32_t)(p[0] | (p[1] << 8));
}

/* ------------------------------------------------------------------ */
/* Layout tables (reverse-engineered field offsets, own encoding)      */
/* ------------------------------------------------------------------ */

/* B8D8 / B8DD serving SNR: carrier, reference-signal type, four chain SNRs
 * (linear, dB = 10*log10(raw/1024), best chain wins; rs 0 = SSB, 1 = TRS). */
struct snr_layout { uint32_t ver; struct pos carrier, rs, snr[4]; };
static const struct snr_layout SNR_B8D8[] = {
    { 0x30000, { 58, 6 }, {  0, 0 }, { {  64, 32 }, {  96, 32 }, { 128, 32 }, { 160, 32 } } },
    { 0x30001, { 58, 6 }, { 64, 1 }, { {  96, 32 }, { 128, 32 }, { 160, 32 }, { 192, 32 } } },
    { 0x30002, { 58, 6 }, { 64, 1 }, { {  96, 32 }, { 128, 32 }, { 160, 32 }, { 192, 32 } } },
};
static const struct snr_layout SNR_B8DD[] = {
    { 0x3000b, { 64, 3 }, { 32, 1 }, { { 640, 32 }, { 736, 32 }, { 832, 32 }, { 928, 32 } } },
};

/* B825 RRC configuration: version, then count fields at fixed offsets, two
 * count-sized arrays, then byte-aligned cell records. Each cell names its
 * modem carrier id (cc); cells are matched to SNR records through it. */
struct cfg_layout {
    uint32_t ver;
    struct pos skip[2];     uint16_t skip_bits[2]; /* counts; element sizes in bits */
    struct pos cell_count;  uint16_t arrays_at, cell_bits;
    struct pos cc, pci, dl_arfcn, ssb_arfcn, band, bw, max_layers;
};
static const uint16_t BW_MHZ_V3[] = { 5, 10, 15, 20, 25, 30, 35, 40, 45, 50,
                                      60, 70, 80, 90, 100, 200, 400, 0 };
static const struct cfg_layout CFG[] = {
    { 0x3000a, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 232,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30009, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 208,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30008, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 208,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30006, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 200,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30005, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 200,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30004, { { 536, 8 }, { 544, 8 } }, { 32, 32 }, { 552, 8 }, 576, 184,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30003, { { 528, 8 }, { 536, 8 } }, { 32, 32 }, { 544, 8 }, 568, 184,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30002, { { 528, 8 }, { 536, 8 } }, { 32, 32 }, { 544, 8 }, 568, 184,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30001, { { 528, 8 }, { 536, 8 } }, { 32, 32 }, { 544, 8 }, 568, 184,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, { 168, 8 } },
    { 0x30000, { { 464, 8 }, { 472, 8 } }, { 32, 32 }, { 480, 8 }, 504, 176,
      { 0, 8 }, { 8, 16 }, { 24, 32 }, { 88, 32 }, { 120, 16 }, { 144, 8 }, {   0, 0 } },
};

/* B823 RRC serving cell (the PCell). */
struct pcell_layout { uint32_t ver; struct pos pci, dl_arfcn; };
static const struct pcell_layout PCELL[] = {
    { 0x30003, { 56, 16 }, { 136, 32 } },
    { 0x30002, { 56, 16 }, { 136, 32 } },
    { 0x30001, { 42, 16 }, { 122, 32 } },
    { 0x30000, { 32, 16 }, { 112, 32 } },
};

/* B97F ML1 searcher measurements: layers of (SSB NR-ARFCN, carrier id, cell
 * list); the serving cell of each layer gives the live cc -> (PCI, ARFCN)
 * map, present even when the helper starts mid-session (B825 only arrives on
 * configuration changes). */
struct live_layout {
    uint32_t ver;
    struct pos layer_count; uint16_t layers_at;
    uint16_t layer_bytes, cell_bytes, beam_bytes;
    struct pos arfcn, cc, ncell, spci;   /* in a layer */
    struct pos pci, nbeam, rsrp, rsrq;   /* in a cell */
};
static const struct live_layout LIVE[] = {
    { 0x30001, { 64, 8 }, 160,  20, 20, 84, { 0, 32 }, { 32, 8 }, { 40, 8 }, { 48, 16 },
      { 0, 16 }, { 32, 8 }, { 64, 32 }, { 96, 32 } },
    { 0x30000, { 64, 8 }, 160,  40, 16, 84, { 0, 32 }, { 32, 8 }, { 40, 8 }, { 48, 16 },
      { 0, 16 }, { 32, 8 }, { 64, 32 }, { 96, 32 } },
    { 0x2000a, { 64, 8 }, 160,  40, 16, 84, { 0, 32 }, { 32, 8 }, { 40, 8 }, { 48, 16 },
      { 0, 16 }, { 32, 8 }, { 64, 32 }, { 96, 32 } },
    { 0x20009, { 64, 8 }, 160,  40, 16, 84, { 0, 32 }, { 32, 8 }, { 40, 8 }, { 48, 16 },
      { 0, 16 }, { 32, 8 }, { 64, 32 }, { 96, 32 } },
};

/* B8A7 MAC CSF report: one record; each report names its resource carrier and
 * carries RI (raw = rank - 1) and wideband CQI. Variable-size reports
 * (rep_bits == 0) are only read from the first report. */
struct csf_layout {
    uint32_t ver;
    struct pos rec_count; uint16_t rec_start;
    struct pos rep_count; uint16_t reps_at, rep_bits;
    struct pos carrier, quantity, ri, cqi;
};
static const struct csf_layout CSF[] = {
    { 0x30005, { 56, 8 },  64, { 32, 32 }, 128, 480, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
    { 0x30004, { 56, 8 },  64, { 32, 32 },  96, 480, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
    { 0x30003, { 56, 8 },  64, { 32, 32 },  96,   0, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
    { 0x30002, { 56, 8 },  64, { 32, 32 },  96,   0, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
    { 0x30001, { 56, 8 },  64, { 32, 32 },  96,   0, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
    { 0x30000, { 56, 8 },  64, { 32, 32 },  64, 480, { 4, 4 }, { 16, 8 }, { 67, 2 }, { 69, 4 } },
};

/* ------------------------------------------------------------------ */
/* Output                                                              */
/* ------------------------------------------------------------------ */

static void emit(const char *line, int n)
{
    if (out_fd < 0 || n <= 0) return;
    ssize_t r = write(out_fd, line, (size_t)n);
    (void)r;
}

/* ------------------------------------------------------------------ */
/* CSI-RS ports (B821 -> rrc_csi.c)                                    */
/* ------------------------------------------------------------------ */

/* rrc_csi.c keeps the per-cell CSI configuration keyed by (PCI, SSB ARFCN)
 * and answers point queries; it has no enumeration API, so the identities
 * to query are remembered here from the other NR logs (B825 serving cells,
 * B97F live searcher map, B823 PCell). Every known cell is re-queried after
 * each B821 record and fully re-emitted every 10 s so a reconnecting client
 * catches up. */
struct ports_cell {
    int      pci;
    uint32_t arfcn;
    int      pcell;       /* 1 = SpCell (rrc_csi SpCell fallback applies) */
    int      last_ports;  /* -1 = never emitted */
    long     last_emit_ms;
};
#define PORTS_CELLS_MAX 24
#define PORTS_REEMIT_MS 10000
static struct ports_cell ports_cells[PORTS_CELLS_MAX];
static int n_ports_cells = 0;

static long mono_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void remember_cell(int pci, uint32_t arfcn, int pcell)
{
    if (pci < 0 || pci > 1000) return;
    for (int i = 0; i < n_ports_cells; i++) {
        if (ports_cells[i].pci == pci && ports_cells[i].arfcn == arfcn) {
            if (pcell) ports_cells[i].pcell = 1;
            return;
        }
    }
    if (n_ports_cells >= PORTS_CELLS_MAX) return;
    ports_cells[n_ports_cells].pci = pci;
    ports_cells[n_ports_cells].arfcn = arfcn;
    ports_cells[n_ports_cells].pcell = pcell;
    ports_cells[n_ports_cells].last_ports = -1;
    ports_cells[n_ports_cells].last_emit_ms = 0;
    n_ports_cells++;
}

static void emit_ports(int force)
{
    long now = mono_ms();
    for (int i = 0; i < n_ports_cells; i++) {
        struct ports_cell *c = &ports_cells[i];
        int p = rrc_csi_ports(c->pci, c->arfcn, c->pcell);
        if (p <= 0) continue;
        if (!force && p == c->last_ports) continue;
        if (!force && now - c->last_emit_ms < 1000) continue;
        char line[128];
        int n = snprintf(line, sizeof(line),
                         "{\"c\":\"ports\",\"ts\":%llu,\"pci\":%d,\"arfcn\":%u,\"ports\":%d}\n",
                         (unsigned long long)(uint64_t)(now), c->pci, c->arfcn, p);
        emit(line, n);
        c->last_ports = p;
        c->last_emit_ms = now;
    }
}

static void decode_b821(const uint8_t *pl, int pn, uint64_t ts);

/* ------------------------------------------------------------------ */
/* Decoders                                                            */
/* ------------------------------------------------------------------ */

static void decode_snr(const uint8_t *pl, int pn, uint16_t code, uint64_t ts)
{
    if (pn < 4) return;
    int count = (code == 0xB8D8) ? (int)(sizeof(SNR_B8D8) / sizeof(SNR_B8D8[0]))
                                 : (int)(sizeof(SNR_B8DD) / sizeof(SNR_B8DD[0]));
    const struct snr_layout *tab = (code == 0xB8D8) ? SNR_B8D8 : SNR_B8DD;
    const struct snr_layout *t = NULL;
    uint32_t ver = nr_ver(pl);
    for (int i = 0; i < count; i++)
        if (tab[i].ver == ver) { t = &tab[i]; break; }
    if (!t || !fits(t->carrier, pn)) return;

    uint32_t cc = at(pl, t->carrier);
    int trs = t->rs.bits ? (int)at(pl, t->rs) : 0;

    double best_db = -1000.0;
    for (int i = 0; i < 4; i++) {
        if (!fits(t->snr[i], pn)) continue;
        uint32_t raw = at(pl, t->snr[i]);
        if (!raw) continue;
        double db = 10.0 * log10((double)raw / 1024.0);
        if (db > best_db) best_db = db;
    }
    if (best_db <= -30.0 || best_db >= 60.0) return;

    char line[160];
    int n = snprintf(line, sizeof(line),
                     "{\"c\":\"snr\",\"ts\":%llu,\"cc\":%u,\"rs\":%d,\"db\":%.1f}\n",
                     (unsigned long long)ts, cc, trs, best_db);
    emit(line, n);
}

static void decode_cfg(const uint8_t *pl, int pn, uint64_t ts)
{
    if (pn < 4) return;
    const struct cfg_layout *t = NULL;
    uint32_t ver = nr_ver(pl);
    for (size_t i = 0; i < sizeof(CFG) / sizeof(CFG[0]); i++)
        if (CFG[i].ver == ver) { t = &CFG[i]; break; }
    if (!t || (int)(t->arrays_at / 8) > pn || !fits(t->cell_count, pn)) return;

    int pos = t->arrays_at / 8;
    for (int k = 0; k < 2; k++)
        pos += (int)at(pl, t->skip[k]) * (t->skip_bits[k] / 8);

    int cc_n = (int)at(pl, t->cell_count);
    int stride = t->cell_bits / 8;
    if (cc_n > 16) cc_n = 16;

    char line[1280];
    int n = snprintf(line, sizeof(line), "{\"c\":\"cfg\",\"ts\":%llu,\"cells\":[",
                     (unsigned long long)ts);
    for (int i = 0; i < cc_n && n > 0 && n < (int)sizeof(line) - 96; i++, pos += stride) {
        if (pos + stride > pn) break;
        const uint8_t *c = pl + pos;
        uint32_t ccid  = at(c, t->cc);
        uint32_t pci   = at(c, t->pci);
        uint32_t dlar  = at(c, t->dl_arfcn);
        uint32_t ssb   = at(c, t->ssb_arfcn);
        uint32_t band  = at(c, t->band);
        uint32_t bwc   = at(c, t->bw);
        uint32_t ml    = at(c, t->max_layers);
        uint16_t bw    = (bwc < 17) ? BW_MHZ_V3[bwc] : 0;
        remember_cell((int)pci, ssb, 0);
        n += snprintf(line + n, sizeof(line) - n,
                      "%s{\"cc\":%u,\"pci\":%u,\"dl\":%u,\"ssb\":%u,\"band\":%u,\"bw\":%u,\"ml\":%u}",
                      i ? "," : "", ccid, pci, dlar, ssb, band, bw, ml);
    }
    if (n > 0 && n < (int)sizeof(line) - 2) {
        line[n++] = ']';
        line[n++] = '}';
        line[n++] = '\n';
        emit(line, n);
    }
}

static void decode_pcell(const uint8_t *pl, int pn, uint64_t ts)
{
    if (pn < 4) return;
    const struct pcell_layout *t = NULL;
    uint32_t ver = nr_ver(pl);
    for (size_t i = 0; i < sizeof(PCELL) / sizeof(PCELL[0]); i++)
        if (PCELL[i].ver == ver) { t = &PCELL[i]; break; }
    if (!t || !fits(t->pci, pn) || !fits(t->dl_arfcn, pn)) return;

    char line[128];
    int n = snprintf(line, sizeof(line),
                     "{\"c\":\"pcell\",\"ts\":%llu,\"pci\":%u,\"dl\":%u}\n",
                     (unsigned long long)ts, at(pl, t->pci), at(pl, t->dl_arfcn));
    emit(line, n);
    remember_cell((int)at(pl, t->pci), at(pl, t->dl_arfcn), 1);
}

static void decode_csf(const uint8_t *pl, int pn, uint64_t ts)
{
    if (pn < 4) return;
    const struct csf_layout *t = NULL;
    uint32_t ver = nr_ver(pl);
    for (size_t i = 0; i < sizeof(CSF) / sizeof(CSF[0]); i++)
        if (CSF[i].ver == ver) { t = &CSF[i]; break; }
    if (!t || !fits(t->rec_count, pn) || !at(pl, t->rec_count)) return;

    int rep = t->rec_start / 8 + t->reps_at / 8;
    if (rep > pn || !fits(t->rep_count, pn)) return;
    int reports = (int)at(pl + t->rec_start / 8, t->rep_count);
    int size = t->rep_bits / 8;
    if (!size && reports > 1) reports = 1;
    if (reports > 16) reports = 16;

    for (int k = 0; k < reports; k++, rep += size) {
        if (rep + 16 > pn) return;
        const uint8_t *r = pl + rep;
        if (at(r, t->quantity) & 32) continue; /* L1-RSRP report: no CSI metrics */
        uint32_t cc = at(r, t->carrier);
        if (cc >= 8) continue;
        char line[128];
        int n = snprintf(line, sizeof(line),
                         "{\"c\":\"csf\",\"ts\":%llu,\"cc\":%u,\"ri\":%u,\"cqi\":%u}\n",
                         (unsigned long long)ts, cc, at(r, t->ri) + 1, at(r, t->cqi));
        emit(line, n);
        if (!size) break;
    }
}

static void decode_live(const uint8_t *pl, int pn, uint64_t ts)
{
    if (pn < 4) return;
    const struct live_layout *t = NULL;
    uint32_t ver = nr_ver(pl);
    for (size_t i = 0; i < sizeof(LIVE) / sizeof(LIVE[0]); i++)
        if (LIVE[i].ver == ver) { t = &LIVE[i]; break; }
    if (!t) return;

    int layers = (int)at(pl, t->layer_count);
    if (layers > 8) layers = 8;
    int pos = t->layers_at / 8;
    for (int l = 0; l < layers && pos + t->layer_bytes <= pn; l++) {
        const uint8_t *L = pl + pos;
        uint32_t arfcn = at(L, t->arfcn);
        int cc = t->cc.bits ? (int)at(L, t->cc) : -1;
        int ncell = (int)at(L, t->ncell);
        uint32_t spci = at(L, t->spci);
        pos += t->layer_bytes;
        for (int c = 0; c < ncell && pos + t->cell_bytes <= pn; c++) {
            const uint8_t *C = pl + pos;
            uint32_t pci = at(C, t->pci);
            uint32_t beams = at(C, t->nbeam);
            pos += t->cell_bytes + (int)beams * t->beam_bytes;
            if (cc >= 0 && cc < 8 && spci != 0xFFFF && pci == spci) {
                remember_cell((int)pci, arfcn, 0);
                char line[128];
                int n = snprintf(line, sizeof(line),
                                 "{\"c\":\"live\",\"ts\":%llu,\"cc\":%d,\"pci\":%u,\"arfcn\":%u}\n",
                                 (unsigned long long)ts, cc, pci, arfcn);
                emit(line, n);
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* B887 MAC PDSCH status: scheduled layers (decimal rank)              */
/* ------------------------------------------------------------------ */

/* Transport-block grants naming their cell by (PCI, NR-ARFCN). The layers
 * field is raw (scheduled layers - 1); only C-RNTI/CS-RNTI grants (rnti 0/3)
 * with a valid CRC flag (<= 1; older 3-bit fields encode other states)
 * count. Accumulated per cell over a 1 s window and emitted as the decimal
 * mean — the rank indicator display. */
struct b887_layout {
    uint32_t ver;
    struct pos rec_count; uint16_t rec_start;
    struct pos item_count; uint16_t items_at, item_bits;
    struct pos pci, arfcn, scell, bw, tb_size, scs_mu, mcs, rbs, rnti, layers, crc, mod, rx;
};
static const struct b887_layout B887[] = {
    { 0x3000d, { 56, 8 }, 64, { 32, 8 }, 64, 288, { 32, 10 }, { 42, 22 }, { 1, 4 }, { 22, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 147, 3 }, { 150, 3 } },
    { 0x3000e, { 56, 8 }, 64, { 32, 8 }, 64, 288, { 32, 10 }, { 42, 22 }, { 1, 4 }, { 22, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 147, 3 }, { 150, 3 } },
    { 0x3000a, { 56, 8 }, 64, { 32, 8 }, 64, 224, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 149, 3 } },
    { 0x30006, { 56, 8 }, 64, { 32, 8 }, 64, 192, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 149, 3 } },
    { 0x30005, { 56, 8 }, 64, { 32, 8 }, 64, 192, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 149, 3 } },
    { 0x30004, { 56, 8 }, 64, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 149, 3 } },
    { 0x30003, { 56, 8 }, 64, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x30002, { 56, 8 }, 64, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x30000, { 56, 8 }, 64, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x20005, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x20004, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x20003, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 146, 3 }, { 150, 2 } },
    { 0x20002, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 0, 0 }, { 0, 0 } },
    { 0x20001, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 22 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 87, 3 }, { 90, 6 }, { 96, 9 }, { 114, 4 }, { 125, 2 }, { 128, 1 }, { 0, 0 }, { 0, 0 } },
    { 0x20000, { 120, 8 }, 128, { 32, 8 }, 64, 160, { 32, 10 }, { 42, 18 }, { 4, 4 }, { 25, 4 }, { 69, 18 }, { 60, 3 }, { 91, 5 }, { 96, 9 }, { 87, 4 }, { 125, 2 }, { 128, 1 }, { 0, 0 }, { 0, 0 } },
    { 0x10010, { 120, 8 }, 192, { 32, 8 }, 64, 544, { 54, 10 }, { 0, 0 }, { 2, 4 }, { 120, 4 }, { 35, 19 }, { 0, 0 }, { 87, 5 }, { 212, 12 }, { 24, 4 }, { 113, 3 }, { 116, 3 }, { 33, 2 }, { 0, 0 } },
    { 0x1000b, { 120, 8 }, 128, { 32, 8 }, 64, 512, { 54, 10 }, { 0, 0 }, { 2, 4 }, { 120, 4 }, { 35, 19 }, { 0, 0 }, { 87, 5 }, { 212, 12 }, { 24, 4 }, { 113, 3 }, { 116, 3 }, { 33, 2 }, { 0, 0 } },
    { 0x10009, { 120, 8 }, 128, { 32, 8 }, 64, 512, { 54, 10 }, { 0, 0 }, { 2, 4 }, { 120, 4 }, { 35, 19 }, { 0, 0 }, { 87, 5 }, { 212, 12 }, { 24, 4 }, { 113, 3 }, { 116, 3 }, { 33, 2 }, { 0, 0 } },
};

struct lay_acc {
    int      pci;
    uint32_t arfcn;
    double   sum;
    int      n;
    long     win_start_ms;
};
#define LAY_CELLS_MAX 8
static struct lay_acc lay_cells[LAY_CELLS_MAX];
static int n_lay_cells = 0;

static void lay_note(int pci, uint32_t arfcn, double layers)
{
    struct lay_acc *c = NULL;
    for (int i = 0; i < n_lay_cells; i++)
        if (lay_cells[i].pci == pci && lay_cells[i].arfcn == arfcn) {
            c = &lay_cells[i];
            break;
        }
    if (!c && n_lay_cells < LAY_CELLS_MAX) {
        c = &lay_cells[n_lay_cells++];
        c->pci = pci;
        c->arfcn = arfcn;
        c->sum = 0;
        c->n = 0;
        c->win_start_ms = 0;
    }
    if (!c) return;

    long now = mono_ms();
    if (!c->win_start_ms) c->win_start_ms = now;
    c->sum += layers;
    c->n++;

    if (now - c->win_start_ms >= 1000 && c->n > 0) {
        char line[160];
        int n = snprintf(line, sizeof(line),
                "{\"c\":\"lay\",\"ts\":%llu,\"pci\":%d,\"arfcn\":%u,\"mean\":%.2f,\"n\":%d}\n",
                (unsigned long long)now, c->pci, c->arfcn, c->sum / c->n, c->n);
        if (n > 0) emit(line, n);
        c->sum = 0;
        c->n = 0;
        c->win_start_ms = now;
    }
}

static void dump_b887(const uint8_t *pl, int pn, uint64_t ts, int known)
{
    if (!b887_dump_path || b887_dump_count >= B821_DUMP_MAX) return;
    FILE *f = fopen(b887_dump_path, b887_dump_count ? "a" : "w");
    if (!f) return;
    fprintf(f, "{\"c\":\"b887%s\",\"ts\":%llu,\"len\":%d,\"ver\":%u,\"hex\":\"",
            known ? "" : "unk", (unsigned long long)ts, pn, nr_ver(pl));
    for (int i = 0; i < pn; i++) fprintf(f, "%02x", pl[i]);
    fprintf(f, "\"}\n");
    fclose(f);
    b887_dump_count++;
}

static void decode_b887(const uint8_t *pl, int pn, uint64_t ts)
{
    if (pn < 4) return;
    /* 3.7 and 3.8 have no table entry of their own; they decode with
     * 3.6's layout (same convention as the reference implementation). */
    uint32_t ver = nr_ver(pl);
    if (ver == 0x30007 || ver == 0x30008) ver = 0x30006;
    const struct b887_layout *t = NULL;
    for (size_t i = 0; i < sizeof(B887) / sizeof(B887[0]); i++)
        if (B887[i].ver == ver) { t = &B887[i]; break; }

    /* Ground-truth dump BEFORE any early return, so an uncovered version
     * still leaves its raw bytes + version in the dump file. */
    dump_b887(pl, pn, ts, t != NULL);

    if (!t || !fits(t->item_count, pn) || !t->item_bits) return;

    int item_bytes = t->item_bits / 8;
    int pos = t->rec_start / 8;
    int records = (int)at(pl, t->rec_count);
    if (records > 8) records = 8;

    for (int r = 0; r < records; r++) {
        if (pos + t->items_at / 8 > pn) return;
        const uint8_t *rec = pl + pos;
        int items = (int)at(rec, t->item_count);
        if (items <= 0 || items > 16) return;
        pos += t->items_at / 8;

        for (int k = 0; k < items; k++, pos += item_bytes) {
            if (pos + item_bytes > pn) return;
            const uint8_t *it = pl + pos;
            uint32_t rnti = at(it, t->rnti);
            if (rnti != 0 && rnti != 3) continue;      /* C-RNTI / CS-RNTI only */
            uint32_t crc = at(it, t->crc);
            if (crc > 1) continue;                     /* ACK-as-NACK etc. */
            int pci = (int)at(it, t->pci);
            if (pci < 0 || pci > 1007) continue;       /* NR PCI range 0..1007 */
            lay_note(pci, at(it, t->arfcn), (double)at(it, t->layers) + 1.0);
        }
    }
}

/* ------------------------------------------------------------------ */
/* DCI callbacks                                                       */
/* ------------------------------------------------------------------ */

static void on_log(unsigned char *ptr, int len)
{
    if (stopping || len < 12) return;
    uint16_t code = (uint16_t)(ptr[2] | (ptr[3] << 8));
    uint64_t ts;
    memcpy(&ts, ptr + 4, 8);
    const uint8_t *pl = ptr + 12;
    int pn = len - 12;

    switch (code) {
    case 0xB8D8:
    case 0xB8DD: decode_snr(pl, pn, code, ts); break;
    case 0xB825: decode_cfg(pl, pn, ts);       break;
    case 0xB823: decode_pcell(pl, pn, ts);     break;
    case 0xB8A7: decode_csf(pl, pn, ts);       break;
    case 0xB97F: decode_live(pl, pn, ts);      break;
    case 0xB887: decode_b887(pl, pn, ts);      break;
    case 0xB821: decode_b821(pl, pn, ts);      break;
    default: break;
    }
}

static void on_event(unsigned char *ptr, int len)
{
    (void)ptr;
    (void)len;
}

/* NR5G RRC OTA packet: the raw first records are still dumped (offline
 * debugging); the payload is fed to the vendored 38.331 decoder, and the
 * per-cell CSI-RS port counts it learns are pushed as "ports" records. */
static void decode_b821(const uint8_t *pl, int pn, uint64_t ts)
{
    rrc_csi_b821(pl, pn);
    emit_ports(0);

    if (pn < 4 || !b821_dump_path || b821_dump_count >= B821_DUMP_MAX) return;

    FILE *f = fopen(b821_dump_path, b821_dump_count ? "a" : "w");
    if (!f) return;
    fprintf(f, "{\"ts\":%llu,\"len\":%d,\"ver\":%u,\"hex\":\"",
            (unsigned long long)ts, pn, nr_ver(pl));
    for (int i = 0; i < pn; i++) fprintf(f, "%02x", pl[i]);
    fprintf(f, "\"}\n");
    fclose(f);
    b821_dump_count++;
}

/* ------------------------------------------------------------------ */
/* Vendor libdiag loading                                              */
/* ------------------------------------------------------------------ */

static void *load_libdiag(void)
{
    void *h = dlopen(LIBDIAG, RTLD_NOW);
    if (h) return h;

    /* A /data binary's default linker namespace cannot load /vendor/lib64 on
     * newer Android: go through the platform's exported sphal namespace. */
    void *dl_android = dlopen("libdl_android.so", RTLD_NOW);
    if (!dl_android) return NULL;
    void *(*get_ns)(const char *) =
        (void *(*)(const char *))dlsym(dl_android, "android_get_exported_namespace");
    if (!get_ns) return NULL;
    void *sphal = get_ns("sphal");
    if (!sphal) return NULL;

    android_dlextinfo info;
    memset(&info, 0, sizeof(info));
    info.flags = ANDROID_DLEXT_USE_NAMESPACE;
    info.library_namespace = sphal;
    return android_dlopen_ext(LIBDIAG, RTLD_NOW, &info);
}

/* ------------------------------------------------------------------ */
/* Socket server                                                       */
/* ------------------------------------------------------------------ */

static int serve_client(void)
{
    int ls = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (ls < 0) return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0;
    strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path) - 2);

    socklen_t addrlen = (socklen_t)(sizeof(sa_family_t) + 1 + strlen(SOCKET_NAME));
    if (bind(ls, (struct sockaddr *)&addr, addrlen) < 0 || listen(ls, 1) < 0) {
        close(ls);
        return -1;
    }

    /* Serve the first client within the startup grace; exit when it goes
     * away so no DIAG subscription is ever left behind. */
    struct pollfd pfd = { .fd = ls, .events = POLLIN };
    if (poll(&pfd, 1, STARTUP_GRACE_MS) <= 0) {
        close(ls);
        return -1;
    }
    int fd = accept4(ls, NULL, NULL, SOCK_CLOEXEC);
    close(ls);
    if (fd < 0) return -1;

    if (client_uid >= 0) {
        struct ucred cred;
        socklen_t cl = sizeof(cred);
        if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &cl) == 0 &&
            cred.uid != 0 && (long)cred.uid != client_uid) {
            close(fd);
            return -1;
        }
    }
    return fd;
}

static void on_term(int sig)
{
    (void)sig;
    stopping = 1;
}

int main(int argc, char **argv)
{
    for (int i = 1; i + 1 < argc; i += 2) {
        if (!strcmp(argv[i], "-uid")) client_uid = atol(argv[i + 1]);
        if (!strcmp(argv[i], "-dump")) b821_dump_path = argv[i + 1];
    }
    if (b821_dump_path) {
        int n = snprintf(b887_dump_buf, sizeof(b887_dump_buf),
                         "%s.b887", b821_dump_path);
        if (n > 0 && (size_t)n < sizeof(b887_dump_buf)) b887_dump_path = b887_dump_buf;
    }

    signal(SIGTERM, on_term);
    signal(SIGINT, on_term);

    void *h = load_libdiag();
    if (!h) {
        fprintf(stderr, "cannot load %s\n", LIBDIAG);
        return 1;
    }

    Diag_LSM_Init               = (lsm_init_t)dlsym(h, "Diag_LSM_Init");
    diag_register_dci_client    = (reg_client_t)dlsym(h, "diag_register_dci_client");
    diag_register_dci_stream_proc = (reg_stream_t)dlsym(h, "diag_register_dci_stream_proc");
    diag_log_stream_config      = (log_cfg_t)dlsym(h, "diag_log_stream_config");
    diag_release_dci_client     = (release_client_t)dlsym(h, "diag_release_dci_client");
    if (!Diag_LSM_Init || !diag_register_dci_client || !diag_register_dci_stream_proc ||
        !diag_log_stream_config) {
        fprintf(stderr, "libdiag symbols missing\n");
        return 1;
    }

    if (!Diag_LSM_Init(NULL)) {
        fprintf(stderr, "Diag_LSM_Init failed\n");
        return 1;
    }

    int sig = SIGCONT;
    uint16_t periph = DIAG_CON_MPSS;
    int r = diag_register_dci_client(&client_handle, &periph, DIAG_PROC_MSM, &sig);
    if (r != DCI_NO_ERROR) {
        fprintf(stderr, "register_dci_client: %d\n", r);
        return 1;
    }
    diag_register_dci_stream_proc(client_handle, on_log, on_event);
    r = diag_log_stream_config(client_handle, 1, (uint16_t *)CODES, NCODES);
    if (r != DCI_NO_ERROR) {
        fprintf(stderr, "log_stream_config: %d\n", r);
        return 1;
    }

    out_fd = serve_client();
    if (out_fd < 0) {
        diag_log_stream_config(client_handle, 0, (uint16_t *)CODES, NCODES);
        if (diag_release_dci_client) diag_release_dci_client(client_handle);
        return 1;
    }

    /* Keep forwarding until the client disconnects or we are told to stop,
     * then unsubscribe and exit so nothing is left behind. poll() reports
     * POLLHUP/POLLERR regardless of the requested events; a timeout simply
     * means the connection is still healthy. */
    while (!stopping) {
        struct pollfd p = { .fd = out_fd, .events = POLLIN | POLLHUP | POLLERR };
        int pr = poll(&p, 1, 1000);
        if (pr < 0) break;
        if (pr > 0 && (p.revents & (POLLHUP | POLLERR))) break;
        /* Periodic full re-emit so a reconnecting client catches up and
         * late-learned cell identities are (re)queried. */
        static long last_reemit = 0;
        long now = mono_ms();
        if (now - last_reemit > PORTS_REEMIT_MS) {
            last_reemit = now;
            emit_ports(1);
        }
    }

    close(out_fd);
    diag_log_stream_config(client_handle, 0, (uint16_t *)CODES, NCODES);
    usleep(500000);
    if (diag_release_dci_client) diag_release_dci_client(client_handle);
    return 0;
}
