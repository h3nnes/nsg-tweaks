package com.nsgmod.band;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Probe hook that reads all 370 hidden/native NR5G property paths and logs any
 * values that change since the previous successful read. The probe is throttled
 * to at most one pass every 3000 ms on calls to v6.g.a(long, DataSource, short).
 *
 * It hooks v6.g.a(long sampleKey, DataSource, short moduleIndex) so it can read
 * the property using the exact sample/module being formatted, and falls back to
 * the current Workspace cursor if no formatting context is active.
 */
public class HiddenFieldProbeHook {

    private static final String TAG = "NSGBandHook";
    private static final long PROBE_INTERVAL_MS = 3000L;

    private final XposedInterface xposed;
    private final ClassLoader loader;

    private boolean ready = false;

    // Workspace / DataSource reflection
    private Field wsJ;           // Workspace.j singleton
    private Field wsG;           // Workspace.g — current sample key (cursor)
    private Field wsModuleIndex; // Workspace.a — current module index
    private Field wsDataSource;  // Workspace.c — current DataSource
    private Class<?> dsClass;    // com.qtrun.sys.DataSource
    private Method dsGetProperty; // DataSource.getProperty(String, int)
    private Method propBMethod;  // Property.b(long) → Iterator
    private Method iterEndMethod; // Iterator.end()Z
    private Method iterValueMethod; // Iterator.value()Object

    private static final ThreadLocal<ProbeContext> probeContext = new ThreadLocal<>();

    private static final class ProbeContext {
        final long sampleKey;
        final int moduleIndex;
        final Object dataSource;

        ProbeContext(long sampleKey, int moduleIndex, Object dataSource) {
            this.sampleKey = sampleKey;
            this.moduleIndex = moduleIndex;
            this.dataSource = dataSource;
        }
    }

    private static final String[] TARGET_KEYS = {
        "NR5G::Cell_Measurements::NR_Cells_ARFCN",
        "NR5G::Cell_Measurements::NR_Cells_Band",
        "NR5G::Cell_Measurements::NR_Cells_BeamIndex",
        "NR5G::Cell_Measurements::NR_Cells_PCI",
        "NR5G::Cell_Measurements::NR_Cells_RSRP",
        "NR5G::Cell_Measurements::NR_Cells_RSRQ",
        "NR5G::Cell_Measurements::NR_Cells_ServingType",
        "NR5G::Cell_Measurements::NR_Cells_SINR",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Bandwidth_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Bandwidth_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_ID_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_ID_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_Bandwidth_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_Bandwidth_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_LocationAndBandwidth_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_LocationAndBandwidth_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_PRB_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_PRB_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_SCS_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_Initial_SCS_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_LocationAndBandwidth_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_LocationAndBandwidth_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_PRB_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_PRB_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWPs_Configured_Count_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWPs_Configured_Count_UL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_SCS_DL",
        "NR5G::Dedicated_Radio_Link::BWP::NR_PCell_BWP_SCS_UL",
        "NR5G::Dedicated_Radio_Link::NR_Aggregated_Bandwidth",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ARFCN_CN_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ARFCN_CN_UL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ARFCN_PointA_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ARFCN_PointA_UL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_Bandwidth_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_Bandwidth_UL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ID_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_ID_UL",
        "NR5G::Dedicated_Radio_Link::NR_BWPs_Configured_Count_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWPs_Configured_Count_UL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_SCS_DL",
        "NR5G::Dedicated_Radio_Link::NR_BWP_SCS_UL",
        "NR5G::Dedicated_Radio_Link::NR_CarrierCount",
        "NR5G::Dedicated_Radio_Link::NR_CarrierCount_UL",
        "NR5G::Dedicated_Radio_Link::NR_Connectivity_Mode",
        "NR5G::Dedicated_Radio_Link::NR_Dedicated_Waveform",
        "NR5G::Dedicated_Radio_Link::NR_Duplex_Mode",
        "NR5G::Dedicated_Radio_Link::NR_RRC_State",
        "NR5G::Dedicated_Radio_Link::NR_ServingCellType",
        "NR5G::Detected_Cells::NR_DetectedCells_ARFCN",
        "NR5G::Detected_Cells::NR_DetectedCells_Band",
        "NR5G::Detected_Cells::NR_DetectedCells_BeamIndex",
        "NR5G::Detected_Cells::NR_DetectedCells_PCI",
        "NR5G::Detected_Cells::NR_DetectedCells_RSRP",
        "NR5G::Detected_Cells::NR_DetectedCells_RSRQ",
        "NR5G::Detected_Cells::NR_DetectedCells_ServingType",
        "NR5G::Detected_Cells::NR_DetectedCells_SINR",
        "NR5G::Downlink_Measurements::NR_APP_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_Carrier_RSSI",
        "NR5G::Downlink_Measurements::NR_CQI_DL",
        "NR5G::Downlink_Measurements::NR_CSI_RSRP",
        "NR5G::Downlink_Measurements::NR_CSI_RSRQ",
        "NR5G::Downlink_Measurements::NR_CSI_SINR",
        "NR5G::Downlink_Measurements::NR_MAC_BLER_DL",
        "NR5G::Downlink_Measurements::NR_MAC_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_MCS_DL",
        "NR5G::Downlink_Measurements::NR_Modulation_DL",
        "NR5G::Downlink_Measurements::NR_ModUsage_1024QAM_DL",
        "NR5G::Downlink_Measurements::NR_ModUsage_16QAM_DL",
        "NR5G::Downlink_Measurements::NR_ModUsage_256QAM_DL",
        "NR5G::Downlink_Measurements::NR_ModUsage_64QAM_DL",
        "NR5G::Downlink_Measurements::NR_ModUsage_QPSK_DL",
        "NR5G::Downlink_Measurements::NR_MRDC_APP_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_MRDC_MAC_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_MRDC_PDCP_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_MRDC_Physical_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_MRDC_RLC_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_Pathloss_DL",
        "NR5G::Downlink_Measurements::NR_PDCP_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_PDSCH_SINR",
        "NR5G::Downlink_Measurements::NR_Physical_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_RBG_Size",
        "NR5G::Downlink_Measurements::NR_RB_Num_Average_DL",
        "NR5G::Downlink_Measurements::NR_RB_Num_Max_DL",
        "NR5G::Downlink_Measurements::NR_RB_Num_Total_DL",
        "NR5G::Downlink_Measurements::NR_RLC_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_SDAP_Throughput_DL",
        "NR5G::Downlink_Measurements::NR_SSB_Beam_RSRP",
        "NR5G::Downlink_Measurements::NR_SSB_Beam_RSRQ",
        "NR5G::Downlink_Measurements::NR_SSB_Beam_SINR",
        "NR5G::Downlink_Measurements::NR_SS_RSRP",
        "NR5G::Downlink_Measurements::NR_SS_RSRP_Max",
        "NR5G::Downlink_Measurements::NR_SS_RSRQ",
        "NR5G::Downlink_Measurements::NR_SS_SINR",
        "NR5G::Downlink_Measurements::NR_TB_Size_Average_DL",
        "NR5G::Downlink_Measurements::NR_TB_Size_Max_DL",
        "NR5G::Downlink_Measurements::NR_TB_Size_Total_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level16_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level1_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level2_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level4_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CCE_Usage_Level8_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CQI_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_CRI_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_BLER_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_MAC_Throughput_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_MCS_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Modulation_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_1024QAM_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_16QAM_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_256QAM_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_64QAM_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_ModUsage_QPSK_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Num_Antenna_Rx",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Num_CSI_Ports",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Num_Layers_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_PDCCH_DCI_Format_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Physical_Throughput_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Physical_Throughput_per_PRB_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Rank_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_RBG_Size",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_RB_Num_Average_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_RB_Num_Max_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_RB_Num_Total_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Scheduled_Count_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_TB_Size_Average_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_TB_Size_Max_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_TB_Size_Total_DL",
        "NR5G::Downlink_Measurements::PCell::NR_PCell_Tx_Mode",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_CQI_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_BLER_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_MAC_Throughput_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_MCS_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Modulation_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_1024QAM_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_16QAM_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_256QAM_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_64QAM_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_ModUsage_QPSK_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Antenna_Rx",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Num_CSI_Ports",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Num_Layers_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Physical_Throughput_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Physical_Throughput_per_PRB_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Rank_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_RB_Num_Average_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_RB_Num_Max_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_RB_Num_Total_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_Scheduled_Count_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_TB_Size_Average_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_TB_Size_Max_DL",
        "NR5G::Downlink_Measurements::SCell::NR_SCell_TB_Size_Total_DL",
        "NR5G::NAS::NR_GUTI_AMF_Pointer",
        "NR5G::NAS::NR_GUTI_AMF_Region_ID",
        "NR5G::NAS::NR_GUTI_AMF_Set_ID",
        "NR5G::NAS::NR_GUTI_TMSI",
        "NR5G::NAS::NR_MM_State",
        "NR5G::NAS::NR_MM_Sub_State",
        "NR5G::NAS::NR_MM_Update_Status",
        "NR5G::RRC::NR_TDD_Pattern1_Num_Slots_DL",
        "NR5G::RRC::NR_TDD_Pattern1_Num_Slots_UL",
        "NR5G::RRC::NR_TDD_Pattern1_Num_Symbols_DL",
        "NR5G::RRC::NR_TDD_Pattern1_Num_Symbols_UL",
        "NR5G::RRC::NR_TDD_Pattern1_Transmission_Periodicity",
        "NR5G::RRC::NR_TDD_Pattern2_Num_Slots_DL",
        "NR5G::RRC::NR_TDD_Pattern2_Num_Slots_UL",
        "NR5G::RRC::NR_TDD_Pattern2_Num_Symbols_DL",
        "NR5G::RRC::NR_TDD_Pattern2_Num_Symbols_UL",
        "NR5G::RRC::NR_TDD_Pattern2_Transmission_Periodicity",
        "NR5G::RRC::NR_TDD_Pattern_Option",
        "NR5G::RRC::NR_TDD_Reference_SCS",
        "NR5G::Serving_Cell::NR_ARFCN_Central",
        "NR5G::Serving_Cell::NR_ARFCN_Central_UL",
        "NR5G::Serving_Cell::NR_ARFCN_PointA",
        "NR5G::Serving_Cell::NR_ARFCN_PointA_UL",
        "NR5G::Serving_Cell::NR_ARFCN_SSB",
        "NR5G::Serving_Cell::NR_ARFCN_SSB_UL",
        "NR5G::Serving_Cell::NR_Band",
        "NR5G::Serving_Cell::NR_Band_UL",
        "NR5G::Serving_Cell::NR_Bandwidth_DL",
        "NR5G::Serving_Cell::NR_Bandwidth_UL",
        "NR5G::Serving_Cell::NR_CellBarred",
        "NR5G::Serving_Cell::NR_CellID",
        "NR5G::Serving_Cell::NR_Frequency_Central",
        "NR5G::Serving_Cell::NR_Frequency_Central_UL",
        "NR5G::Serving_Cell::NR_Frequency_SSB",
        "NR5G::Serving_Cell::NR_Frequency_SSB_UL",
        "NR5G::Serving_Cell::NR_GSCN_Central",
        "NR5G::Serving_Cell::NR_GSCN_Central_UL",
        "NR5G::Serving_Cell::NR_GSCN_SSB",
        "NR5G::Serving_Cell::NR_GSCN_SSB_UL",
        "NR5G::Serving_Cell::NR_IntraFreq_Reselection",
        "NR5G::Serving_Cell::NR_MCC",
        "NR5G::Serving_Cell::NR_MNC",
        "NR5G::Serving_Cell::NR_PCI",
        "NR5G::Serving_Cell::NR_RANAC",
        "NR5G::Serving_Cell::NR_SCS",
        "NR5G::Serving_Cell::NR_ServCellIndex",
        "NR5G::Serving_Cell::NR_ServingCellGroup_ID",
        "NR5G::Serving_Cell::NR_SSB_Beam_Index",
        "NR5G::Serving_Cell::NR_SSB_Beam_Num",
        "NR5G::Serving_Cell::NR_SSB_Subcarrier_Offset",
        "NR5G::Serving_Cell::NR_TAC",
        "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB",
        "NR5G::Serving_Cell::SCell::NR_SCell_ARFCN_SSB_UL",
        "NR5G::Serving_Cell::SCell::NR_SCell_Band",
        "NR5G::Serving_Cell::SCell::NR_SCell_Band_UL",
        "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_DL",
        "NR5G::Serving_Cell::SCell::NR_SCell_Bandwidth_UL",
        "NR5G::Serving_Cell::SCell::NR_SCell_PCI",
        "NR5G::Serving_Cell::SCell::NR_SCell_SCS",
        "NR5G::Serving_Cell::SCell::NR_SCell_SSB_Beam_Index",
        "NR5G::Serving_Cell::SUL::NR_ARFCN_SUL",
        "NR5G::Serving_Cell::SUL::NR_Band_SUL",
        "NR5G::Serving_Cell::SUL::NR_Bandwidth_SUL",
        "NR5G::Serving_Cell::SUL::NR_SCS_SUL",
        "NR5G::Session_Management::SM5G_5QI_Session_1",
        "NR5G::Session_Management::SM5G_5QI_Session_2",
        "NR5G::Session_Management::SM5G_5QI_Session_3",
        "NR5G::Session_Management::SM5G_5QI_Session_4",
        "NR5G::Session_Management::SM5G_AMBR_DL_Session_1",
        "NR5G::Session_Management::SM5G_AMBR_DL_Session_2",
        "NR5G::Session_Management::SM5G_AMBR_DL_Session_3",
        "NR5G::Session_Management::SM5G_AMBR_DL_Session_4",
        "NR5G::Session_Management::SM5G_AMBR_UL_Session_1",
        "NR5G::Session_Management::SM5G_AMBR_UL_Session_2",
        "NR5G::Session_Management::SM5G_AMBR_UL_Session_3",
        "NR5G::Session_Management::SM5G_AMBR_UL_Session_4",
        "NR5G::Session_Management::SM5G_ContextType_Session_1",
        "NR5G::Session_Management::SM5G_ContextType_Session_2",
        "NR5G::Session_Management::SM5G_ContextType_Session_3",
        "NR5G::Session_Management::SM5G_ContextType_Session_4",
        "NR5G::Session_Management::SM5G_DNN_Session_1",
        "NR5G::Session_Management::SM5G_DNN_Session_2",
        "NR5G::Session_Management::SM5G_DNN_Session_3",
        "NR5G::Session_Management::SM5G_DNN_Session_4",
        "NR5G::Session_Management::SM5G_GFBR_DL_Session_1",
        "NR5G::Session_Management::SM5G_GFBR_DL_Session_2",
        "NR5G::Session_Management::SM5G_GFBR_DL_Session_3",
        "NR5G::Session_Management::SM5G_GFBR_DL_Session_4",
        "NR5G::Session_Management::SM5G_GFBR_UL_Session_1",
        "NR5G::Session_Management::SM5G_GFBR_UL_Session_2",
        "NR5G::Session_Management::SM5G_GFBR_UL_Session_3",
        "NR5G::Session_Management::SM5G_GFBR_UL_Session_4",
        "NR5G::Session_Management::SM5G_IP_Address_Session_1",
        "NR5G::Session_Management::SM5G_IP_Address_Session_2",
        "NR5G::Session_Management::SM5G_IP_Address_Session_3",
        "NR5G::Session_Management::SM5G_IP_Address_Session_4",
        "NR5G::Session_Management::SM5G_MFBR_DL_Session_1",
        "NR5G::Session_Management::SM5G_MFBR_DL_Session_2",
        "NR5G::Session_Management::SM5G_MFBR_DL_Session_3",
        "NR5G::Session_Management::SM5G_MFBR_DL_Session_4",
        "NR5G::Session_Management::SM5G_MFBR_UL_Session_1",
        "NR5G::Session_Management::SM5G_MFBR_UL_Session_2",
        "NR5G::Session_Management::SM5G_MFBR_UL_Session_3",
        "NR5G::Session_Management::SM5G_MFBR_UL_Session_4",
        "NR5G::Session_Management::SM5G_NSSAI_SD_Session_1",
        "NR5G::Session_Management::SM5G_NSSAI_SD_Session_2",
        "NR5G::Session_Management::SM5G_NSSAI_SD_Session_3",
        "NR5G::Session_Management::SM5G_NSSAI_SD_Session_4",
        "NR5G::Session_Management::SM5G_NSSAI_SST_Session_1",
        "NR5G::Session_Management::SM5G_NSSAI_SST_Session_2",
        "NR5G::Session_Management::SM5G_NSSAI_SST_Session_3",
        "NR5G::Session_Management::SM5G_NSSAI_SST_Session_4",
        "NR5G::Session_Management::SM5G_PDU_ID_Session_1",
        "NR5G::Session_Management::SM5G_PDU_ID_Session_2",
        "NR5G::Session_Management::SM5G_PDU_ID_Session_3",
        "NR5G::Session_Management::SM5G_PDU_ID_Session_4",
        "NR5G::Session_Management::SM5G_PDU_State_Session_1",
        "NR5G::Session_Management::SM5G_PDU_State_Session_2",
        "NR5G::Session_Management::SM5G_PDU_State_Session_3",
        "NR5G::Session_Management::SM5G_PDU_State_Session_4",
        "NR5G::Uplink_Measurements::NR_Access_Delay",
        "NR5G::Uplink_Measurements::NR_Access_Reason",
        "NR5G::Uplink_Measurements::NR_APP_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_C_RNTI",
        "NR5G::Uplink_Measurements::NR_MAC_BLER_UL",
        "NR5G::Uplink_Measurements::NR_MAC_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_MCS_UL",
        "NR5G::Uplink_Measurements::NR_Modulation_UL",
        "NR5G::Uplink_Measurements::NR_ModUsage_16QAM_UL",
        "NR5G::Uplink_Measurements::NR_ModUsage_256QAM_UL",
        "NR5G::Uplink_Measurements::NR_ModUsage_64QAM_UL",
        "NR5G::Uplink_Measurements::NR_ModUsage_QPSK_UL",
        "NR5G::Uplink_Measurements::NR_MRDC_APP_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_MRDC_MAC_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_MRDC_PDCP_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_MRDC_Physical_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_MRDC_RLC_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_PDCP_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_Physical_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_Power_Headroom",
        "NR5G::Uplink_Measurements::NR_Power_Tx_PRACH",
        "NR5G::Uplink_Measurements::NR_Power_Tx_PUCCH",
        "NR5G::Uplink_Measurements::NR_Power_Tx_PUSCH",
        "NR5G::Uplink_Measurements::NR_Power_Tx_SRS",
        "NR5G::Uplink_Measurements::NR_PRACH_Config",
        "NR5G::Uplink_Measurements::NR_RACH_Access_Result",
        "NR5G::Uplink_Measurements::NR_RACH_Contention",
        "NR5G::Uplink_Measurements::NR_RACH_Logical_Root_Index",
        "NR5G::Uplink_Measurements::NR_RACH_Num_Attempt",
        "NR5G::Uplink_Measurements::NR_RACH_Preamble_Format",
        "NR5G::Uplink_Measurements::NR_RACH_RA_RNTI",
        "NR5G::Uplink_Measurements::NR_RACH_SSB_ID",
        "NR5G::Uplink_Measurements::NR_RACH_T_RNTI",
        "NR5G::Uplink_Measurements::NR_RB_Num_Average_UL",
        "NR5G::Uplink_Measurements::NR_RB_Num_Max_UL",
        "NR5G::Uplink_Measurements::NR_RB_Num_Total_UL",
        "NR5G::Uplink_Measurements::NR_RLC_BLER_UL",
        "NR5G::Uplink_Measurements::NR_RLC_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_SDAP_Throughput_UL",
        "NR5G::Uplink_Measurements::NR_TB_Size_Average_UL",
        "NR5G::Uplink_Measurements::NR_TB_Size_Max_UL",
        "NR5G::Uplink_Measurements::NR_TB_Size_Total_UL",
        "NR5G::Uplink_Measurements::NR_Timing_Advance",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_CCE_Usage_Level16_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_CCE_Usage_Level1_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_CCE_Usage_Level2_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_CCE_Usage_Level4_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_CCE_Usage_Level8_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Hopping_Flag_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_MAC_BLER_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_MAC_Throughput_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_MCS_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Modulation_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_ModUsage_16QAM_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_ModUsage_256QAM_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_ModUsage_64QAM_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_ModUsage_QPSK_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Num_Layers_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PDCCH_DCI_Format_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_per_PRB_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_SUL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Physical_Throughput_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PUCCH_Format0_Usage_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PUCCH_Format1_Usage_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PUCCH_Format2_Usage_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PUCCH_Format3_Usage_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_PUCCH_Format4_Usage_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Rank_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_RB_Num_Average_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_RB_Num_Max_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_RB_Num_Total_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Scheduled_Count_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_TB_Size_Average_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_TB_Size_Max_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_TB_Size_Total_UL",
        "NR5G::Uplink_Measurements::PCell::NR_PCell_Tx_Mode_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Hopping_Flag_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_MAC_BLER_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_MAC_Throughput_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_MCS_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Modulation_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_ModUsage_16QAM_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_ModUsage_256QAM_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_ModUsage_64QAM_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_ModUsage_QPSK_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Num_Layers_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_PDCCH_DCI_Format_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Physical_Throughput_per_PRB_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Physical_Throughput_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Power_Tx_PUSCH",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Rank_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_RB_Num_Average_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_RB_Num_Max_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_RB_Num_Total_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Scheduled_Count_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_TB_Size_Average_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_TB_Size_Max_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_TB_Size_Total_UL",
        "NR5G::Uplink_Measurements::SCell::NR_SCell_Tx_Mode_UL"
    };

    // Last logged non-null value for each field, keyed by field name.
    private final Map<String, Object> lastLoggedValues = new HashMap<>();
    private volatile long lastProbeTime = 0L;

    public HiddenFieldProbeHook(XposedInterface xposed, ClassLoader loader) {
        this.xposed = xposed;
        this.loader = loader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> wsClass = loader.loadClass("com.qtrun.sys.Workspace");
            dsClass = loader.loadClass("com.qtrun.sys.DataSource");
            Class<?> propClass = loader.loadClass("com.qtrun.sys.Property");
            Class<?> iterClass = loader.loadClass("com.qtrun.sys.Property$Iterator");

            wsJ = wsClass.getField("j");
            wsG = wsClass.getDeclaredField("g"); wsG.setAccessible(true);
            wsModuleIndex = wsClass.getField("a");
            wsDataSource = wsClass.getField("c");

            dsGetProperty = dsClass.getMethod("getProperty", String.class, int.class);
            propBMethod = propClass.getMethod("b", long.class);
            iterEndMethod = iterClass.getMethod("end");
            iterValueMethod = iterClass.getMethod("value");

            ready = true;
        } catch (Exception e) {
            Log.e(TAG, "HiddenFieldProbeHook: initReflection failed: " + e);
        }
    }

    public void install() {
        if (!ready) {
            Log.w(TAG, "HiddenFieldProbeHook: skipping install — reflection not ready");
            return;
        }
        installGridValueLabelHook();
    }

    private void installGridValueLabelHook() {
        try {
            Class<?> gridValueLabelClass = loader.loadClass("v6.g");
            Method aMethod = gridValueLabelClass.getMethod("a", long.class, dsClass, short.class);

            xposed.hook(aMethod).intercept(new Hooker() {
                @Override
                public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
                    Object sampleKeyObj = chain.getArg(0);
                    Object ds = chain.getArg(1);
                    Object moduleIndexObj = chain.getArg(2);

                    long now = SystemClock.elapsedRealtime();
                    long timeSinceLastProbe = now - lastProbeTime;
                    boolean shouldProbe = timeSinceLastProbe >= PROBE_INTERVAL_MS;

                    if (shouldProbe && sampleKeyObj instanceof Number && moduleIndexObj instanceof Number) {
                        lastProbeTime = now;
                        probeContext.set(new ProbeContext(
                                ((Number) sampleKeyObj).longValue(),
                                ((Number) moduleIndexObj).intValue(),
                                ds));
                        try {
                            runProbePass();
                        } finally {
                            probeContext.remove();
                        }
                    }

                    return chain.proceed();
                }
            });
            Log.i(TAG, "HiddenFieldProbeHook: grid value label hook installed");
        } catch (Exception e) {
            Log.e(TAG, "HiddenFieldProbeHook: install v6.g failed: " + e);
        }
    }

    /** Runs one full probe pass over all TARGET_KEYS and logs a summary. */
    private void runProbePass() {
        int fieldsWithData = 0;
        int fieldsChanged = 0;

        for (String key : TARGET_KEYS) {
            try {
                ProbeResult result = readField(key);
                if (result.hasData) {
                    fieldsWithData++;
                    if (result.changed) {
                        fieldsChanged++;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "HiddenFieldProbeHook: probe warning for " + key + ": " + e);
            }
        }

        Log.i(TAG, "HiddenFieldProbeHook: NR probe complete: " + fieldsWithData
                + "/" + TARGET_KEYS.length + " fields with data this cycle, "
                + fieldsChanged + " changed since last cycle");
    }

    private static final class ProbeResult {
        final boolean hasData;
        final boolean changed;

        ProbeResult(boolean hasData, boolean changed) {
            this.hasData = hasData;
            this.changed = changed;
        }
    }

    /**
     * Reads the named property for the currently active context and returns whether
     * the field had any data and whether its value changed since the last probe.
     */
    private ProbeResult readField(String key) throws Exception {
        ProbeContext ctx = probeContext.get();

        long sampleKey;
        int moduleIndex;
        Object ds;

        if (ctx != null) {
            sampleKey = ctx.sampleKey;
            moduleIndex = ctx.moduleIndex;
            ds = ctx.dataSource;
        } else {
            try {
                Object ws = wsJ.get(null);
                if (ws == null) {
                    return new ProbeResult(false, false);
                }
                sampleKey = wsG.getLong(ws);
                moduleIndex = ((Number) wsModuleIndex.get(ws)).intValue();
                ds = wsDataSource.get(ws);
            } catch (Exception e) {
                Log.w(TAG, "HiddenFieldProbeHook: Workspace fallback failed for " + key + ": " + e);
                return new ProbeResult(false, false);
            }
        }

        if (ds == null) {
            return new ProbeResult(false, false);
        }

        Object property = dsGetProperty.invoke(ds, key, moduleIndex);
        if (property == null) {
            return new ProbeResult(false, false);
        }

        Object iterator = propBMethod.invoke(property, sampleKey);
        if (iterator == null) {
            return new ProbeResult(false, false);
        }

        if ((boolean) iterEndMethod.invoke(iterator)) {
            return new ProbeResult(false, false);
        }

        Object value = iterValueMethod.invoke(iterator);
        if (value == null) {
            return new ProbeResult(false, false);
        }

        Object previous = lastLoggedValues.get(key);
        boolean changed = previous == null || !Objects.equals(previous, value);
        lastLoggedValues.put(key, value);

        if (changed) {
            Log.i(TAG, "HiddenFieldProbeHook: NR " + key + "=" + value
                    + " class=" + value.getClass().getName()
                    + " sampleKey=" + sampleKey
                    + " moduleIndex=" + moduleIndex);
        }

        return new ProbeResult(true, changed);
    }
}
