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
#include <unistd.h>

#define LIBDIAG       "/vendor/lib64/libdiag.so"
#define SOCKET_NAME   "nsgmod_diagd"
#define DIAG_PROC_MSM 0
#define DIAG_CON_MPSS (1 << 1)
#define DCI_NO_ERROR  1001

#define STARTUP_GRACE_MS    8000

static const uint16_t CODES[] = { 0xB8DD, 0xB8D8, 0xB825, 0xB823, 0xB8A7, 0xB97F, 0xB821 };
#define NCODES ((int)(sizeof(CODES) / sizeof(CODES[0])))

/* Raw B821 sample dump for offline UPER decoder development: the first
 * records received are written as one hex line each to -dump <file>. */
#define B821_DUMP_MAX 200
static const char *b821_dump_path = NULL;
static int b821_dump_count = 0;

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
    case 0xB821: decode_b821(pl, pn, ts);      break;
    default: break;
    }
}

static void on_event(unsigned char *ptr, int len)
{
    (void)ptr;
    (void)len;
}

/* NR5G RRC OTA packet: kept raw for now; a minimal UPER decoder for
 * CSI-RS port counts per serving cell is the next increment. Version word
 * and payload sizes are dumped to -dump <file> for decoder development. */
static void decode_b821(const uint8_t *pl, int pn, uint64_t ts)
{
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
    }

    close(out_fd);
    diag_log_stream_config(client_handle, 0, (uint16_t *)CODES, NCODES);
    usleep(500000);
    if (diag_release_dci_client) diag_release_dci_client(client_handle);
    return 0;
}
