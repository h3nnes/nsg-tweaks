package com.nsgmod.band;

import android.util.Log;

/**
 * Symbol table for NSG qtrun v4.8.14 (package com.qtrun.QuickTest, R8 map-id 739e1d25...).
 *
 * <p>This module targets exactly one NSG build. Every obfuscated name below was verified
 * against the decompiled v4.8.14 sources.
 * Names that only a single hook needs live as private constants inside that hook; this
 * class holds the ones shared by several hooks.
 *
 * <p>Naming: the constant describes the role of the class; the trailing comment
 * documents its members and purpose.
 */
public final class Nsg {

    private static final String TAG = "NSGBandHook";

    private Nsg() {
    }

    // -----------------------------------------------------------------------
    // Classes that keep their real names (not renamed by R8, used via JNI)
    // -----------------------------------------------------------------------
    public static final String WORKSPACE      = "com.qtrun.sys.Workspace";
    public static final String DATA_SOURCE    = "com.qtrun.sys.DataSource";
    public static final String PROPERTY       = "com.qtrun.sys.Property";
    public static final String PROPERTY_ITER  = "com.qtrun.sys.Property$Iterator";
    /** Attribute base class (fields: a=key String, b=format String, c=index int, d=Property). */
    public static final String SYS_ATTR_A     = "l8";
    /** Attribute subclass of l8 used for value bindings. */
    public static final String SYS_ATTR_B     = "m8";

    // -----------------------------------------------------------------------
    // Grid-page builder family (metric pages built from a row/col grid)
    // -----------------------------------------------------------------------
    /** Grid builder; adds label/value cells (methods M/N/O). */
    public static final String GRID_BUILDER   = "r31";
    /** Grid element base (row position field "b"). */
    public static final String GRID_ELEMENT   = "xg0";
    /** Abstract grid-page fragment base; static g0(builder) callback. */
    public static final String GRID_PAGE      = "yg0";
    /** Grid container that owns the element list. */
    public static final String GRID_D         = "ah0";
    /** Label cell (text f, alignment g, span h). */
    public static final String GRID_LABEL     = "bh0";
    /** Value cell with one attribute (field g). */
    public static final String GRID_VALUE     = "ch0";
    /** Value cell with several attributes. */
    public static final String GRID_MULTI     = "dh0";

    // -----------------------------------------------------------------------
    // Page fragments (metric pages and cell tables)
    // -----------------------------------------------------------------------
    /** LTE "CAMatrixUL" page. */
    public static final String PAGE_LTE_CA_MATRIX_UL   = "v00";
    /** LTE "CAMatrix" (DL) page. */
    public static final String PAGE_LTE_CA_MATRIX_DL   = "w00";
    /** NR-NSA "LTE CA Matrix" page. */
    public static final String PAGE_NSA_LTE_CA_MATRIX  = "ua0";
    /** NR-NSA "Dual Connectivity" page. */
    public static final String PAGE_NSA_DUAL_CONN      = "ta0";
    /** NR-NSA "CA Matrix UL" page. */
    public static final String PAGE_NSA_CA_MATRIX_UL   = "ma0";
    /** NR-SA "CA Matrix" (DL) page. */
    public static final String PAGE_SA_CA_MATRIX_DL    = "db0";
    /** NR-SA "CA Matrix UL" page. */
    public static final String PAGE_SA_CA_MATRIX_UL    = "eb0";
    /** NR-SA "DedicatedMode" page. */
    public static final String PAGE_SA_DEDICATED_MODE  = "kb0";

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Loads a class from the NSG class loader without initialising it.
     * Logs a warning and returns {@code null} if the class is missing.
     */
    public static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "Nsg: class not found in NSG v4.8.14: " + name);
            return null;
        }
    }
}
