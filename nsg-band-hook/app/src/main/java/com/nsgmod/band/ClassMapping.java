package com.nsgmod.band;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps logical (qtrun v4.8.8) class names, method names and field names to
 * their runtime equivalents for the current NSG flavor.
 *
 * <p>On qtrun the mapping is identity. On google-play the obfuscated names moved
 * to different packages/classes and ProGuard also renamed many methods/fields.
 * This class supplies the known high-confidence equivalents and returns
 * {@code null} for classes that are unavailable on gplay.
 */
public final class ClassMapping {

    private static final String TAG = "NSGBandHook";

    private static final Map<String, String> GPLAY_OVERRIDES;
    private static final Set<String> GPLAY_IDENTITY;

    /** Method-name overrides for gplay: key = "logicalClass|logicalMethod". */
    private static final Map<String, String> GPLAY_METHOD_OVERRIDES;
    /** Field-name overrides for gplay: key = "logicalClass|logicalField". */
    private static final Map<String, String> GPLAY_FIELD_OVERRIDES;
    /** Integer-constant overrides for gplay: key = "logicalClass|logicalConstant". */
    private static final Map<String, Integer> GPLAY_CONSTANT_OVERRIDES;

    private static final Map<String, String> QTRUN_CLASS_OVERRIDES;
    private static final Map<String, String> QTRUN_FIELD_OVERRIDES;
    private static final Map<String, String> QTRUN_METHOD_OVERRIDES;
    private static final Map<String, Integer> QTRUN_CONSTANT_OVERRIDES;

    static {
        Set<String> identity = new HashSet<>();
        // Classes confirmed identical in both qtrun and gplay v4.8.8.
        identity.add("y7.a");
        identity.add("w1.a");
        identity.add("d.d");
        GPLAY_IDENTITY = Collections.unmodifiableSet(identity);

        Map<String, String> map = new HashMap<>();

        // Package renames (qtrun -> gplay).
        // a8 cell-fragment family -> j6
        map.put("a8.f", "j6.f");
        map.put("a8.f$a", "j6.f$a");
        map.put("a8.h", "j6.h");
        map.put("a8.h$a", "j6.h$a");
        map.put("a8.i", "j6.i");
        map.put("a8.i$a", "j6.i$a");
        map.put("a8.d", "j6.d");
        map.put("a8.d$b", "j6.d$b");
        map.put("a8.b", "j6.b");
        map.put("a8.b$a", "j6.b$a");
        map.put("a8.b$b", "j6.b$b");

        // v6 grid-builder family -> e5 (entire package moved in gplay)
        map.put("v6.a", "e5.a");
        map.put("v6.b", "e5.b");
        map.put("v6.e", "e5.e");
        map.put("v6.f", "e5.f");
        map.put("v6.g", "e5.g");
        map.put("v6.d", "e5.d");

        // k2.a grid builder -> b6.e (gplay k2.a is a different interface)
        map.put("k2.a", "b6.e");

        // LTE / NR-SA / NR-NSA matrix fragments moved to n6 / q6 / p6 on gplay.
        // qtrun e8 package is ACRA on gplay; h8 package is ACRA on gplay.
        map.put("e8.b", "n6.b");
        map.put("e8.a", "n6.a");
        map.put("h8.b", "q6.b");

        // k8 adapter/playback family -> t6
        map.put("k8.c", "t6.c");
        map.put("k8.f", "t6.f");
        map.put("k8.j", "t6.j");

        // t7 settings/menu family -> c6
        map.put("t7.g0", "c6.q0");
        map.put("t7.e0", "c6.n0");
        map.put("t7.p", "c6.v");
        map.put("t7.w0", "c6.h1");
        map.put("t7.e", "c6.f");

        // u7 signaling -> d6
        map.put("u7.f", "d6.g");
        map.put("u7.a", "d6.a");

        // d7/d8 KPI fragments -> m5/m6
        map.put("d7.i", "m5.h");
        map.put("d7.i$c", "m5.h$c");
        map.put("d7.i$k", "m5.h$k");
        map.put("d8.i", "m6.i");

        // misc synthetic / utility renames
        map.put("a4.h", "c6.h");
        map.put("ma.a", "z8.a");
        map.put("f7.b", "o5.b");

        // g8 matrix fragments -> p6 (g8.b intentionally left unmapped per special case).
        map.put("g8.h", "p6.h");
        map.put("g8.i", "p6.i");

        // NR-NSA / NR-SA CA Matrix UL fragments moved to p6 / q6 on gplay.
        map.put("g8.b", "p6.b");
        map.put("h8.c", "q6.c");
        map.put("h8.h", "q6.h");

        // Experiments settings fragment moved to c6.z on gplay.
        map.put("t7.t", "c6.z");

        // AdvancedAdapter moved from x6.b to g5.b on gplay.
        map.put("x6.b", "g5.b");

        map.put("h7.b", "q5.b");
        map.put("h7.e", "q5.e");
        map.put("d7.a", "m5.a");
        map.put("sr0", "m5.f");
        map.put("js", "c6.a0");
        map.put("sf0", "y5.a");
        map.put("hw0", "x5.d");
        map.put("e20", "x5.a");
        map.put("or0", "c5.a");
        map.put("oi0", "x5.c");

        GPLAY_OVERRIDES = Collections.unmodifiableMap(map);

        Map<String, String> methodMap = new HashMap<>();
        // k2.a builder methods: qtrun r/s/t -> gplay n/o/p; j -> f
        methodMap.put("k2.a|r", "n");
        methodMap.put("k2.a|s", "o");
        methodMap.put("k2.a|t", "p");
        methodMap.put("k2.a|j", "f");
        // v6.b static factory: qtrun k0(k2.a) -> gplay j0(b6.e)
        methodMap.put("v6.b|k0", "j0");
        // PlaybackControlsFragment seekbar sync: qtrun i0(float) -> gplay h0(float)
        methodMap.put("k8.f|i0", "h0");
        // LTE CA Matrix DL builder method: qtrun e8.b.n0() -> gplay n6.b.m0()
        methodMap.put("e8.b|n0", "m0");
        // NR-NSA EUTRA CA Matrix DL builder method: qtrun g8.i.n0() -> gplay p6.i.m0()
        methodMap.put("g8.i|n0", "m0");
        // NR-SA CA Matrix DL builder method: qtrun h8.b.o0() -> gplay q6.b.n0()
        methodMap.put("h8.b|o0", "n0");
        methodMap.put("h8.b|Q", "Q");
        // NR-NSA / NR-SA CA Matrix UL builder method: qtrun l0(Context) -> gplay k0(Context)
        methodMap.put("g8.b|l0", "k0");
        methodMap.put("h8.c|l0", "k0");
        methodMap.put("h8.h|l0", "k0");
        // NR-SA KPI fragment builder method: qtrun d8.i.l0(Context) -> gplay m6.i.k0(Context)
        methodMap.put("d8.i|l0", "k0");
        // LTE CA Matrix UL builder method: qtrun e8.a.l0(Context) -> gplay n6.a.k0(Context)
        methodMap.put("e8.a|l0", "k0");
        // Cell-DB WHERE-clause builder: qtrun ma.a.j(...) -> gplay z8.a.h(...)
        methodMap.put("ma.a|j", "h");
        // Timestamp formatter: qtrun ma.a.m(Date) -> gplay v8.a.c(Date)
        methodMap.put("ma.a|m", "c");
        // Note: ma.a in qtrun is Installation.kt; on gplay its functionality is split.
        // The timestamp formatter lives in v8.a.c(Date), so hooks that need it fall
        // back to v8.a directly (see GranularSeekBarHook / RtPlayHook).
        // Experiments settings fragment: qtrun t7.t.i0(String) -> gplay c6.z.h0(String)
        methodMap.put("t7.t|i0", "h0");
        // ActivityResultLauncher launch: qtrun d.d.c(Object) -> gplay d.d.a(Object)
        methodMap.put("d.d|c", "a");
        // LegendManager methods shifted on gplay:
        // qtrun c(b,double)->float  -> gplay e(b,double)
        // qtrun a(b,double)->Integer -> gplay d(b,double)
        methodMap.put("com.qtrun.legend.LegendManager|c", "e");
        methodMap.put("com.qtrun.legend.LegendManager|a", "d");
        methodMap.put("js|f0", "h0");
        methodMap.put("com.qtrun.legend.Presentation.PresentationManager|a", "d");
        GPLAY_METHOD_OVERRIDES = Collections.unmodifiableMap(methodMap);

        Map<String, String> fieldMap = new HashMap<>();
        // v6.d internal element list: qtrun "a" -> gplay "b"
        fieldMap.put("v6.d|a", "b");
        // a8.d$b sources array: qtrun "d" -> gplay "e"
        fieldMap.put("a8.d$b|d", "e");
        // a8.b$b sources array: qtrun "e" -> gplay "f"
        fieldMap.put("a8.b$b|e", "f");
        // k8.c current sample key: qtrun "c" -> gplay "d"
        fieldMap.put("k8.c|c", "d");
        // k8.c per-bucket count: qtrun "d" -> gplay "e"
        fieldMap.put("k8.c|d", "e");
        // u7.a synthetic click-listener fields: qtrun a/b -> gplay b/c
        fieldMap.put("u7.a|a", "b");
        fieldMap.put("u7.a|b", "c");
        // com.qtrun.sys.Workspace static/instance fields:
        // qtrun singleton j, moduleIndex a, listeners b, dispatcher d, modules e,
        //           timestamp attr f, current key g, max key h, current date i
        // gplay   singleton k, moduleIndex b, listeners c, dispatcher e, modules f,
        //           timestamp attr g, current key h, max key i, current date j
        fieldMap.put("com.qtrun.sys.Workspace|j", "k");
        fieldMap.put("com.qtrun.sys.Workspace|a", "b");
        fieldMap.put("com.qtrun.sys.Workspace|b", "c");
        fieldMap.put("com.qtrun.sys.Workspace|c", "d");
        fieldMap.put("com.qtrun.sys.Workspace|d", "e");
        fieldMap.put("com.qtrun.sys.Workspace|e", "f");
        fieldMap.put("com.qtrun.sys.Workspace|f", "g");
        fieldMap.put("com.qtrun.sys.Workspace|g", "h");
        fieldMap.put("com.qtrun.sys.Workspace|h", "i");
        fieldMap.put("com.qtrun.sys.Workspace|i", "j");
        // a4.h synthetic load-completion fields (gplay c6.h): qtrun a/b/c -> gplay b/d/c
        fieldMap.put("a4.h|a", "b");
        fieldMap.put("a4.h|b", "d");
        fieldMap.put("a4.h|c", "c");
        // t7.e synthetic file-picker fields (gplay c6.f): qtrun a/b -> gplay b/c
        fieldMap.put("t7.e|a", "b");
        fieldMap.put("t7.e|b", "c");
        // CirclePageIndicator fields on gplay: a->b, e->f, g->h, l->m, q->r (k stays the same)
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|a", "b");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|e", "f");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|g", "h");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|l", "m");
        fieldMap.put("com.qtrun.widget.viewpagerindicator.CirclePageIndicator|q", "r");
        // com.qtrun.sys.a (Attribute) fields are identical in both flavors, but keep
        // explicit identity entries so hooks can use runtimeFieldName safely.
        fieldMap.put("com.qtrun.sys.a|a", "a");
        fieldMap.put("com.qtrun.sys.a|b", "b");
        fieldMap.put("com.qtrun.sys.a|c", "c");
        fieldMap.put("com.qtrun.sys.a|d", "d");
        // ProgressTextView fields shifted by one on gplay:
        // qtrun i(float progress), j(boolean showBar) -> gplay j(float), k(boolean)
        fieldMap.put("com.qtrun.widget.textview.ProgressTextView|j", "k");
        // LegendManager singleton field: qtrun e -> gplay f
        fieldMap.put("com.qtrun.legend.LegendManager|e", "f");
        fieldMap.put("com.qtrun.legend.Presentation.PresentationManager|e", "f");
        fieldMap.put("com.qtrun.legend.Presentation.PresentationManager|a", "b");
        fieldMap.put("com.qtrun.legend.Presentation.PresentationManager|b", "c");
        fieldMap.put("com.qtrun.legend.LegendManager|b", "c");
        fieldMap.put("com.qtrun.legend.LegendManager|c", "d");
        fieldMap.put("or0|b", "c");
        GPLAY_FIELD_OVERRIDES = Collections.unmodifiableMap(fieldMap);

        Map<String, Integer> constantMap = new HashMap<>();
        // a4.h / c6.h synthetic switch case used for log-file load completion.
        // qtrun R8 assigns case 5; gplay R8 assigns case 0.
        constantMap.put("a4.h|LOAD_COMPLETE_CASE", 0);
        GPLAY_CONSTANT_OVERRIDES = Collections.unmodifiableMap(constantMap);

        Map<String, String> qtrunClassMap = new HashMap<>();
        qtrunClassMap.put("a8.b", "ee");
        qtrunClassMap.put("a8.b$b", "nc");
        qtrunClassMap.put("a8.b$a", "de");
        qtrunClassMap.put("a8.d", "dq");
        qtrunClassMap.put("a8.d$b", "b10");
        qtrunClassMap.put("a8.d$a", "cq");
        qtrunClassMap.put("a8.f", "z00");
        qtrunClassMap.put("a8.f$a", "nc");
        qtrunClassMap.put("a8.h", "pa0");
        qtrunClassMap.put("a8.h$a", "nc");
        qtrunClassMap.put("a8.i", "hb0");
        qtrunClassMap.put("a8.i$a", "b10");
        qtrunClassMap.put("e8.a", "w00");
        qtrunClassMap.put("e8.b", "x00");
        qtrunClassMap.put("h8.b", "eb0");
        qtrunClassMap.put("h8.c", "fb0");
        qtrunClassMap.put("h8.h", "lb0");
        qtrunClassMap.put("g8.b", "na0");
        qtrunClassMap.put("g8.h", "ua0");
        qtrunClassMap.put("g8.i", "va0");
        qtrunClassMap.put("d7.a", "xa");
        qtrunClassMap.put("d7.i", "v00");
        qtrunClassMap.put("d7.i$c", "v00");
        qtrunClassMap.put("d7.i$k", "v00");
        qtrunClassMap.put("d8.i", "mb0");
        qtrunClassMap.put("f7.b", "sd");
        qtrunClassMap.put("h7.b", "yd");
        qtrunClassMap.put("h7.e", "la0");
        qtrunClassMap.put("u7.f", "y80");
        qtrunClassMap.put("u7.a", "sp");
        qtrunClassMap.put("t7.e", "s2");
        qtrunClassMap.put("t7.e0", "y30");
        qtrunClassMap.put("t7.g0", "b40");
        qtrunClassMap.put("t7.w0", "w31");
        qtrunClassMap.put("t7.t", "zp");
        qtrunClassMap.put("t7.p", "t31");
        qtrunClassMap.put("a4.h", "u2");
        qtrunClassMap.put("ma.a", "q21");
        qtrunClassMap.put("k8.c", "r00");
        qtrunClassMap.put("k8.f", "se0");
        qtrunClassMap.put("k8.b", "eq");
        qtrunClassMap.put("k8.a", "la");
        qtrunClassMap.put("k8.j", "u31");
        qtrunClassMap.put("k2.a", "t31");
        qtrunClassMap.put("v6.a", "yg0");
        qtrunClassMap.put("v6.b", "zg0");
        qtrunClassMap.put("v6.d", "bh0");
        qtrunClassMap.put("v6.e", "ch0");
        qtrunClassMap.put("v6.f", "dh0");
        qtrunClassMap.put("v6.g", "eh0");
        qtrunClassMap.put("w1.a", "ce0");
        qtrunClassMap.put("x6.b", "j3");
        qtrunClassMap.put("u6.a", "jd0");
        qtrunClassMap.put("o7.a", "e20");
        qtrunClassMap.put("z6.a", "kh");
        qtrunClassMap.put("d.d", "i2");
        qtrunClassMap.put("androidx.preference.c", "jf0");
        qtrunClassMap.put("com.qtrun.sys.a", "l8");
        qtrunClassMap.put("com.qtrun.sys.b", "m8");
        qtrunClassMap.put("com.qtrun.nsg.AdvancedActivity$a", "mx");
        QTRUN_CLASS_OVERRIDES = Collections.unmodifiableMap(qtrunClassMap);

        Map<String, String> qtrunFieldMap = new HashMap<>();
        qtrunFieldMap.put("k2.a|d", "b");
        qtrunFieldMap.put("k2.a|c", "a");
        qtrunFieldMap.put("v6.b|Y", "X");
        qtrunFieldMap.put("v6.b|Z", "Y");
        qtrunFieldMap.put("v6.f|j", "i");
        qtrunFieldMap.put("v6.f|k", "j");
        qtrunFieldMap.put("v6.f|i", "h");
        qtrunFieldMap.put("e8.b|Z", "Y");
        qtrunFieldMap.put("g8.i|Z", "Y");
        qtrunFieldMap.put("h8.b|a0", "Z");
        qtrunFieldMap.put("k8.f|X", "W");
        qtrunFieldMap.put("k8.f|Y", "X");
        qtrunFieldMap.put("k8.f|Z", "Y");
        qtrunFieldMap.put("k8.f|a0", "Z");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity$a|a", "b");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|K", "H");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|I", "F");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|J", "G");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|M", "J");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|E", "B");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|F", "C");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|H", "E");
        qtrunFieldMap.put("com.qtrun.nsg.AdvancedActivity|L", "I");
        qtrunFieldMap.put("androidx.preference.c|Y", "X");
        qtrunFieldMap.put("androidx.preference.Preference|C", "B");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|c1", "b1");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|b1", "a1");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|d1", "c1");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|X0", "W0");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|Y0", "X0");
        qtrunFieldMap.put("com.qtrun.udv.header.HeaderCGIFragment|Z0", "Y0");
        QTRUN_FIELD_OVERRIDES = Collections.unmodifiableMap(qtrunFieldMap);

        Map<String, String> qtrunMethodMap = new HashMap<>();
        qtrunMethodMap.put("com.qtrun.legend.LegendManager|c", "b");
        qtrunMethodMap.put("k2.a|B", "Z");
        qtrunMethodMap.put("k2.a|c", "f");
        qtrunMethodMap.put("k2.a|A", "X");
        qtrunMethodMap.put("k2.a|e", "j");
        qtrunMethodMap.put("k2.a|f", "k");
        qtrunMethodMap.put("k2.a|g", "m");
        qtrunMethodMap.put("k2.a|i", "n");
        qtrunMethodMap.put("k2.a|j", "p");
        qtrunMethodMap.put("k2.a|m", "H");
        qtrunMethodMap.put("k2.a|o", "J");
        qtrunMethodMap.put("k2.a|p", "K");
        qtrunMethodMap.put("k2.a|q", "L");
        qtrunMethodMap.put("k2.a|r", "M");
        qtrunMethodMap.put("k2.a|s", "N");
        qtrunMethodMap.put("k2.a|t", "O");
        qtrunMethodMap.put("k2.a|x", "V");
        qtrunMethodMap.put("v6.b|k0", "g0");
        qtrunMethodMap.put("v6.b|I", "E");
        qtrunMethodMap.put("v6.b|l0", "h0");
        qtrunMethodMap.put("v6.b|g", "e");
        qtrunMethodMap.put("v6.b|h", "g");
        qtrunMethodMap.put("k8.f|i0", "e0");
        qtrunMethodMap.put("k8.f|I", "E");
        qtrunMethodMap.put("e8.b|n0", "j0");
        qtrunMethodMap.put("e8.b|l0", "h0");
        qtrunMethodMap.put("e8.b|i0", "e0");
        qtrunMethodMap.put("e8.b|j0", "f0");
        qtrunMethodMap.put("e8.b|k0", "g0");
        qtrunMethodMap.put("e8.b|m0", "i0");
        qtrunMethodMap.put("e8.b|O", "K");
        qtrunMethodMap.put("e8.b|I", "E");
        qtrunMethodMap.put("g8.i|n0", "j0");
        qtrunMethodMap.put("g8.i|l0", "h0");
        qtrunMethodMap.put("g8.i|i0", "e0");
        qtrunMethodMap.put("g8.i|j0", "f0");
        qtrunMethodMap.put("g8.i|k0", "g0");
        qtrunMethodMap.put("g8.i|m0", "i0");
        qtrunMethodMap.put("g8.i|O", "K");
        qtrunMethodMap.put("g8.i|Q", "M");
        qtrunMethodMap.put("g8.i|I", "E");
        qtrunMethodMap.put("h8.b|o0", "k0");
        qtrunMethodMap.put("h8.b|Q", "M");
        qtrunMethodMap.put("h8.b|n0", "j0");
        qtrunMethodMap.put("h8.b|i0", "e0");
        qtrunMethodMap.put("h8.b|j0", "f0");
        qtrunMethodMap.put("h8.b|k0", "g0");
        qtrunMethodMap.put("h8.b|l0", "h0");
        qtrunMethodMap.put("h8.b|m0", "i0");
        qtrunMethodMap.put("h8.b|O", "K");
        qtrunMethodMap.put("h8.b|I", "E");
        qtrunMethodMap.put("h8.c|l0", "h0");
        qtrunMethodMap.put("h8.c|i0", "e0");
        qtrunMethodMap.put("h8.c|j0", "f0");
        qtrunMethodMap.put("h8.h|l0", "h0");
        qtrunMethodMap.put("h8.h|i0", "e0");
        qtrunMethodMap.put("h8.h|j0", "f0");
        qtrunMethodMap.put("e8.a|l0", "h0");
        qtrunMethodMap.put("e8.a|i0", "e0");
        qtrunMethodMap.put("e8.a|j0", "f0");
        qtrunMethodMap.put("d8.i|l0", "h0");
        qtrunMethodMap.put("g8.b|l0", "h0");
        qtrunMethodMap.put("g8.h|l0", "h0");
        qtrunMethodMap.put("ma.a|j", "f");
        qtrunMethodMap.put("ma.a|o", "h");
        qtrunMethodMap.put("ma.a|p", "i");
        qtrunMethodMap.put("t7.t|i0", "f0");
        qtrunMethodMap.put("t7.t|j0", "h0");
        qtrunMethodMap.put("t7.t|d", "e0");
        qtrunMethodMap.put("d.d|c", "k");
        qtrunMethodMap.put("t7.p|a", "b");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|g", "e");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|h", "f");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|i", "g");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|c", "b");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|f", "c");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|j", "h");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|l", "i");
        qtrunMethodMap.put("com.qtrun.sys.Workspace|n", "j");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|J", "B");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|I", "A");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|K", "C");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|G", "y");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|H", "z");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|L", "D");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|M", "E");
        qtrunMethodMap.put("com.qtrun.nsg.AdvancedActivity|u", "h");
        qtrunMethodMap.put("a8.b$b|h", "g");
        qtrunMethodMap.put("a8.b$b|c", "b");
        qtrunMethodMap.put("a8.b$b|g", "f");
        qtrunMethodMap.put("a8.d$b|g", "f");
        qtrunMethodMap.put("a8.d$b|f", "e");
        qtrunMethodMap.put("f7.b|d", "e");
        qtrunMethodMap.put("a8.f|I", "E");
        qtrunMethodMap.put("a8.h|I", "E");
        qtrunMethodMap.put("a8.i|I", "E");
        qtrunMethodMap.put("com.qtrun.udv.header.HeaderRFFragment|I", "E");
        qtrunMethodMap.put("com.qtrun.udv.header.HeaderRFFragment|h", "g");
        qtrunMethodMap.put("com.qtrun.udv.header.HeaderRFFragment|k0", "g0");
        qtrunMethodMap.put("a4.h|e", "f");
        qtrunMethodMap.put("t7.w0|c", "r");
        qtrunMethodMap.put("t7.w0|e", "D");
        QTRUN_METHOD_OVERRIDES = Collections.unmodifiableMap(qtrunMethodMap);

        Map<String, Integer> qtrunConstantMap = new HashMap<>();
        qtrunConstantMap.put("a4.h|LOAD_COMPLETE_CASE", 0);
        QTRUN_CONSTANT_OVERRIDES = Collections.unmodifiableMap(qtrunConstantMap);
    }

    private ClassMapping() {
    }

    /**
     * Returns the runtime class name for the given logical class name, or {@code null}
     * if the class is not mapped for the current flavor.
     */
    public static String runtimeName(String logicalName, ClassLoader loader) {
        FlavorDetector.Flavor flavor = FlavorDetector.detect(loader);
        if (flavor == FlavorDetector.Flavor.QTRUN) {
            if (QTRUN_CLASS_OVERRIDES.containsKey(logicalName)) {
                return QTRUN_CLASS_OVERRIDES.get(logicalName);
            }
            return logicalName;
        }
        if (flavor != FlavorDetector.Flavor.GPLAY) {
            return logicalName;
        }

        if (GPLAY_OVERRIDES.containsKey(logicalName)) {
            return GPLAY_OVERRIDES.get(logicalName);
        }

        // Classes confirmed identical in both flavors.
        if (GPLAY_IDENTITY.contains(logicalName)) {
            return logicalName;
        }

        // Classes that are not obfuscated / are stable across flavors (AndroidX, SDK,
        // com.qtrun.sys, com.qtrun.nsg, etc.) keep their logical name on gplay.
        if (isLikelyStable(logicalName)) {
            return logicalName;
        }

        return null;
    }

    private static boolean isLikelyStable(String logicalName) {
        return logicalName.startsWith("android.")
                || logicalName.startsWith("androidx.")
                || logicalName.startsWith("java.")
                || logicalName.startsWith("javax.")
                || logicalName.startsWith("com.qtrun.")
                || logicalName.startsWith("kotlin.")
                || logicalName.startsWith("org.");
    }

    /**
     * Returns the runtime method name for the given logical class and method,
     * or {@code logicalMethod} if no gplay override exists.
     */
    public static String runtimeMethodName(String logicalClass, String logicalMethod,
                                           ClassLoader loader) {
        FlavorDetector.Flavor flavor = FlavorDetector.detect(loader);
        if (flavor == FlavorDetector.Flavor.QTRUN) {
            String key = logicalClass + "|" + logicalMethod;
            if (QTRUN_METHOD_OVERRIDES.containsKey(key)) {
                return QTRUN_METHOD_OVERRIDES.get(key);
            }
            return logicalMethod;
        }
        if (flavor != FlavorDetector.Flavor.GPLAY) {
            return logicalMethod;
        }
        String key = logicalClass + "|" + logicalMethod;
        if (GPLAY_METHOD_OVERRIDES.containsKey(key)) {
            return GPLAY_METHOD_OVERRIDES.get(key);
        }
        return logicalMethod;
    }

    /**
     * Returns the runtime field name for the given logical class and field,
     * or {@code logicalField} if no gplay override exists.
     */
    public static String runtimeFieldName(String logicalClass, String logicalField,
                                        ClassLoader loader) {
        FlavorDetector.Flavor flavor = FlavorDetector.detect(loader);
        if (flavor == FlavorDetector.Flavor.QTRUN) {
            String key = logicalClass + "|" + logicalField;
            if (QTRUN_FIELD_OVERRIDES.containsKey(key)) {
                return QTRUN_FIELD_OVERRIDES.get(key);
            }
            return logicalField;
        }
        if (flavor != FlavorDetector.Flavor.GPLAY) {
            return logicalField;
        }
        String key = logicalClass + "|" + logicalField;
        if (GPLAY_FIELD_OVERRIDES.containsKey(key)) {
            return GPLAY_FIELD_OVERRIDES.get(key);
        }
        return logicalField;
    }

    /**
     * Returns the runtime int constant for the given logical key, or
     * {@code qtrunValue} if no gplay override exists.
     */
    public static int runtimeIntConstant(String logicalKey, int qtrunValue,
                                          ClassLoader loader) {
        FlavorDetector.Flavor flavor = FlavorDetector.detect(loader);
        if (flavor == FlavorDetector.Flavor.QTRUN) {
            if (QTRUN_CONSTANT_OVERRIDES.containsKey(logicalKey)) {
                return QTRUN_CONSTANT_OVERRIDES.get(logicalKey);
            }
            return qtrunValue;
        }
        if (flavor != FlavorDetector.Flavor.GPLAY) {
            return qtrunValue;
        }
        if (GPLAY_CONSTANT_OVERRIDES.containsKey(logicalKey)) {
            return GPLAY_CONSTANT_OVERRIDES.get(logicalKey);
        }
        return qtrunValue;
    }

    /**
     * Looks up a method on {@code runtimeClass} using the flavor-aware method name.
     * Useful when the gplay ProGuard renaming changed a method name but the
     * parameter types stayed the same.
     */
    public static Method getMethod(Class<?> runtimeClass, String logicalClass,
                                   String logicalMethod, ClassLoader loader,
                                   Class<?>... parameterTypes) throws NoSuchMethodException {
        String runtimeMethod = runtimeMethodName(logicalClass, logicalMethod, loader);
        return runtimeClass.getMethod(runtimeMethod, parameterTypes);
    }

    /**
     * Looks up a declared method on {@code runtimeClass} using the flavor-aware
     * method name.
     */
    public static Method getDeclaredMethod(Class<?> runtimeClass, String logicalClass,
                                           String logicalMethod, ClassLoader loader,
                                           Class<?>... parameterTypes) throws NoSuchMethodException {
        String runtimeMethod = runtimeMethodName(logicalClass, logicalMethod, loader);
        return runtimeClass.getDeclaredMethod(runtimeMethod, parameterTypes);
    }

    /**
     * Returns {@code true} if the logical class is mapped and can be loaded.
     */
    public static boolean hasClass(String logicalName, ClassLoader loader) {
        return loadClass(logicalName, loader) != null;
    }

    /**
     * Loads the class for the given logical name using the supplied class loader.
     *
     * <p>Returns {@code null} and logs a warning if the class is not mapped or cannot
     * be loaded.
     */
    public static Class<?> loadClass(String logicalName, ClassLoader loader) {
        String runtimeName = runtimeName(logicalName, loader);
        if (runtimeName == null) {
            Log.w(TAG, "ClassMapping: no mapping for logical class " + logicalName
                    + " on flavor " + FlavorDetector.detect(loader));
            return null;
        }
        try {
            return Class.forName(runtimeName, false, loader);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "ClassMapping: could not load runtime class " + runtimeName
                    + " for logical " + logicalName + ": " + e);
            return null;
        }
    }
}
