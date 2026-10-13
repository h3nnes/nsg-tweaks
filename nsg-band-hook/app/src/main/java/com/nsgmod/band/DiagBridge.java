package com.nsgmod.band;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

/**
 * Bridge to the nsgmod-diagd DCI helper.
 *
 * The helper (shipped in the module APK, spawned as root through su) registers
 * its own Diag Consumer Interface client with the modem and pushes per-record
 * NDJSON lines over the abstract unix socket "nsgmod_diagd". DCI subscriptions
 * are per-client and concurrent, so NSG's own (DM-mode) diag session is not
 * disturbed.
 *
 * Time synchronisation: every record is anchored to the wall clock at arrival
 * (DCI delivery adds only a few ms over NSG's own DM session, which receives
 * the same packets). Hooks query values for the NSG sample timestamp currently
 * being rendered, and the bridge returns the nearest record within a
 * tolerance, so both streams describe the same modem events.
 */
public final class DiagBridge {

    private static final String TAG = "NSGBandHook";
    private static final String MODULE_PKG = "com.nsgmod.band";
    private static final String SOCKET_NAME = "nsgmod_diagd";

    /** Max age (ms) of a helper record considered valid for a query. */
    private static final long MATCH_TOLERANCE_MS = 2000;

    // ------------------------------------------------------------------
    // Record state
    // ------------------------------------------------------------------

    private static final class SnrRec {
        final long   atMs;
        final int    cc;
        final boolean trs;   // true = CSI-RS (TRS) record
        final double db;
        SnrRec(long atMs, int cc, boolean trs, double db) {
            this.atMs = atMs; this.cc = cc; this.trs = trs; this.db = db;
        }
    }

    private static final class CfgCell {
        int pci, dl, ssb, band, bw, maxLayers;
    }

    private static final class CsfRec {
        final long atMs;
        final int  cc, ri, cqi;
        CsfRec(long atMs, int cc, int ri, int cqi) {
            this.atMs = atMs; this.cc = cc; this.ri = ri; this.cqi = cqi;
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<SnrRec> snrRecords = new ArrayDeque<>();
    private static final ArrayDeque<CsfRec> csfRecords = new ArrayDeque<>();
    private static final Map<Integer, CfgCell> cfgByCc = new ConcurrentHashMap<>();
    private static final ArrayDeque<long[]> pcellRecords = new ArrayDeque<>(); // {atMs, pci, dl}

    private static volatile boolean started = false;
    private static volatile boolean dciAvailable = false;
    private static volatile Thread readerThread;
    private static volatile long lastHelperSpawnMs = 0;

    /** (NSG sample key, wall clock) anchor pair captured at render time. */
    private static volatile long anchorKey = Long.MIN_VALUE;
    private static volatile long anchorWallMs;

    private DiagBridge() {}

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the bridge if the device qualifies: an NSG-recognised Qualcomm
     * diag environment. Non-Qualcomm targets (MTK, HiSilicon, Shannon/Exynos)
     * never reach the helper spawn; DCI init failure disables it at runtime.
     */
    public static void install(ClassLoader loader) {
        if (started) return;
        synchronized (DiagBridge.class) {
            if (started) return;
            started = true;
        }
        if (!isQualcommTarget(loader)) {
            Log.i(TAG, "DiagBridge: not a Qualcomm target, staying inactive");
            return;
        }
        readerThread = new Thread(DiagBridge::runLoop, "NSGMod-DiagBridge");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** True when the bridge has a live helper connection delivering data. */
    public static boolean isAvailable() {
        return dciAvailable;
    }

    /**
     * Anchors the NSG sample-key timeline to the wall clock. Called from the
     * grid render hook with the exact sample key being displayed, so helper
     * records can be matched against what NSG shows regardless of the
     * underlying key units.
     */
    public static void noteSampleKey(long key) {
        anchorKey = key;
        anchorWallMs = System.currentTimeMillis();
    }

    /** Maps an NSG sample key to its estimated wall-clock instant. */
    private static long wallForSampleKey(long key) {
        long ak = anchorKey;
        if (ak == Long.MIN_VALUE) return System.currentTimeMillis();
        return anchorWallMs + (key - ak);
    }

    /** Latest NSG sample key observed at render time, or null. */
    public static Long currentSampleKey() {
        long ak = anchorKey;
        return ak == Long.MIN_VALUE ? null : ak;
    }

    /** True when NSG's own prerequisite detection reports a Qualcomm modem. */
    private static boolean isQualcommTarget(ClassLoader loader) {
        try {
            Class<?> appCls = ClassMapping.loadClass("com.qtrun.sys.Application", loader);
            Object app = null;
            try {
                app = appCls.getMethod("h").invoke(null);
            } catch (Exception ignored) {
                for (java.lang.reflect.Field f : appCls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && f.getType() == appCls) {
                        f.setAccessible(true);
                        app = f.get(null);
                        break;
                    }
                }
            }
            if (app == null) return false;
            String result = (String) appCls.getMethod("getString", String.class)
                    .invoke(app, "prerequisite.result");
            if (result == null) return false;
            return findKey(new JSONObject(result), "qualcomm") != null;
        } catch (Throwable t) {
            Log.w(TAG, "DiagBridge: prerequisite check failed: " + t);
            return false;
        }
    }

    /** Depth-first search for a JSON key anywhere in the object tree. */
    private static Object findKey(Object node, String key) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has(key)) return o.opt(key);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                Object found = findKey(o.opt(it.next()), key);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object found = findKey(a.opt(i), key);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Connection loop
    // ------------------------------------------------------------------

    private static void runLoop() {
        android.content.Context ctx = appContext();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ensureHelper(ctx);
                connectAndRead();
            } catch (Throwable t) {
                Log.w(TAG, "DiagBridge: " + t);
            }
            dciAvailable = false;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static android.content.Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (android.content.Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void ensureHelper(android.content.Context ctx) throws Exception {
        if (ctx == null) throw new IllegalStateException("no application context");

        File bin = new File(ctx.getCacheDir(), "nsgmod-diagd");
        if (!bin.exists() || bin.length() == 0) {
            String abi = android.os.Build.SUPPORTED_ABIS != null
                    && android.os.Build.SUPPORTED_ABIS.length > 0
                    && android.os.Build.SUPPORTED_ABIS[0].contains("arm64")
                    ? "arm64-v8a" : "armeabi-v7a";
            android.content.Context moduleCtx =
                    ctx.createPackageContext(MODULE_PKG, android.content.Context.CONTEXT_IGNORE_SECURITY);
            try (ZipFile apk = new ZipFile(moduleCtx.getApplicationInfo().sourceDir)) {
                ZipEntry entry = apk.getEntry("assets/helper/" + abi + "/nsgmod-diagd");
                if (entry == null) throw new IllegalStateException("helper binary missing from module APK");
                try (InputStream in = apk.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(bin)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
        }

        long now = SystemClock_UptimeMillis();
        boolean helperRunning = helperSocketAlive();
        if (!helperRunning && now - lastHelperSpawnMs > 5000) {
            lastHelperSpawnMs = now;
            int uid = android.os.Process.myUid();
            String cmd = "chmod 755 '" + bin.getAbsolutePath() + "'"
                    + " && exec setsid '" + bin.getAbsolutePath() + "' -uid " + uid
                    + " </dev/null >/dev/null 2>&1 &";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        }
    }

    private static long SystemClock_UptimeMillis() {
        return android.os.SystemClock.uptimeMillis();
    }

    private static boolean helperSocketAlive() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT), 300);
            s.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void connectAndRead() throws Exception {
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        dciAvailable = true;
        Log.i(TAG, "DiagBridge: connected to helper");

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()), 16384)) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handleRecord(new JSONObject(line));
                } catch (Throwable t) {
                    Log.w(TAG, "DiagBridge: bad record: " + t);
                }
            }
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
        Log.i(TAG, "DiagBridge: helper disconnected");
    }

    private static void handleRecord(JSONObject o) {
        long now = System.currentTimeMillis();
        String c = o.optString("c");
        switch (c) {
            case "snr": {
                SnrRec rec = new SnrRec(now, o.optInt("cc", -1),
                        o.optInt("rs", 0) == 1, o.optDouble("db", -999));
                synchronized (LOCK) {
                    snrRecords.addLast(rec);
                    while (snrRecords.size() > 512) snrRecords.removeFirst();
                }
                break;
            }
            case "cfg": {
                JSONArray cells = o.optJSONArray("cells");
                if (cells == null) break;
                for (int i = 0; i < cells.length(); i++) {
                    JSONObject cell = cells.optJSONObject(i);
                    if (cell == null) continue;
                    CfgCell cc = new CfgCell();
                    cc.pci = cell.optInt("pci", -1);
                    cc.dl = cell.optInt("dl", -1);
                    cc.ssb = cell.optInt("ssb", -1);
                    cc.band = cell.optInt("band", -1);
                    cc.bw = cell.optInt("bw", -1);
                    cc.maxLayers = cell.optInt("ml", -1);
                    cfgByCc.put(i, cc);
                }
                break;
            }
            case "pcell": {
                synchronized (LOCK) {
                    pcellRecords.addLast(new long[]{now, o.optInt("pci", -1), o.optInt("dl", -1)});
                    while (pcellRecords.size() > 32) pcellRecords.removeFirst();
                }
                break;
            }
            case "csf": {
                CsfRec rec = new CsfRec(now, o.optInt("cc", -1),
                        o.optInt("ri", -1), o.optInt("cqi", -1));
                synchronized (LOCK) {
                    csfRecords.addLast(rec);
                    while (csfRecords.size() > 256) csfRecords.removeFirst();
                }
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Queries (called from UI hooks with the NSG sample time being rendered)
    // ------------------------------------------------------------------

    /** Drops records older than the retention window. */
    private static void prune(long now) {
        synchronized (LOCK) {
            while (!snrRecords.isEmpty() && now - snrRecords.peekFirst().atMs > 10000) {
                snrRecords.removeFirst();
            }
            while (!csfRecords.isEmpty() && now - csfRecords.peekFirst().atMs > 10000) {
                csfRecords.removeFirst();
            }
            while (!pcellRecords.isEmpty() && now - pcellRecords.peekFirst()[0] > 30000) {
                pcellRecords.removeFirst();
            }
        }
    }

    /**
     * CSI SNR (TRS-based serving SNR) for the carrier identified by PCI and
     * SSB ARFCN, for the NSG sample key being rendered. Falls back to the
     * newest record when the instant is unknown. Returns null when no
     * matching record exists.
     */
    public static Double csiSnr(int pci, long ssbArfcn, Long sampleKey) {
        int cc = ccForCell(pci, ssbArfcn);
        if (cc < 0) return null;
        return bestSnr(cc, true, sampleKey);
    }

    /** SS SINR (SSB-based serving SNR) for the same cell identity. */
    public static Double ssSnr(int pci, long ssbArfcn, Long sampleKey) {
        int cc = ccForCell(pci, ssbArfcn);
        if (cc < 0) return null;
        return bestSnr(cc, false, sampleKey);
    }

    /** Latest RI (decimal rank) reported by B8A7 for the carrier. */
    public static Integer rankIndicator(int pci, long ssbArfcn) {
        int cc = ccForCell(pci, ssbArfcn);
        if (cc < 0) return null;
        synchronized (LOCK) {
            Integer best = null;
            for (CsfRec r : csfRecords) {
                if (r.cc != cc) continue;
                if (r.ri < 1 || r.ri > 8) continue;
                best = r.ri; // records arrive in order; last wins
            }
            return best;
        }
    }

    private static int ccForCell(int pci, long ssbArfcn) {
        for (Map.Entry<Integer, CfgCell> e : cfgByCc.entrySet()) {
            CfgCell c = e.getValue();
            if (c.pci == pci && (ssbArfcn <= 0 || c.ssb == ssbArfcn)) {
                return e.getKey();
            }
        }
        // Fall back to PCI-only match (ARFCN may not be reported yet).
        for (Map.Entry<Integer, CfgCell> e : cfgByCc.entrySet()) {
            if (e.getValue().pci == pci) return e.getKey();
        }
        return -1;
    }

    private static Double bestSnr(int cc, boolean trs, Long sampleKey) {
        long now = System.currentTimeMillis();
        prune(now);
        long ref = sampleKey != null ? wallForSampleKey(sampleKey) : now;
        synchronized (LOCK) {
            SnrRec best = null;
            long bestDist = Long.MAX_VALUE;
            for (SnrRec r : snrRecords) {
                if (r.cc != cc || r.trs != trs) continue;
                long dist = Math.abs(r.atMs - ref);
                if (dist > MATCH_TOLERANCE_MS) continue;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = r;
                }
            }
            return best != null ? best.db : null;
        }
    }
}
