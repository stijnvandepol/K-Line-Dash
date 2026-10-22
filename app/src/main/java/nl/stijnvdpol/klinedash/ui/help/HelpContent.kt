package nl.stijnvdpol.klinedash.ui.help

import androidx.annotation.StringRes
import nl.stijnvdpol.klinedash.R
import nl.stijnvdpol.klinedash.ui.SetupSection

/**
 * One piece of a help topic: an optional heading, its text, and optionally a button that opens the
 * SETUP section the text talks about. All text is a string resource, so help works offline and is
 * read in one place.
 */
internal class HelpItem(@StringRes val heading: Int?, @StringRes val body: Int, val link: SetupSection? = null)

/**
 * The topics of SETUP > Help, in list order. [isCollapsible] topics are a list of questions that
 * open on a tap; the others read top to bottom. [showsAdapterList] puts the list of adapters that
 * work (the one in `SupportedAdapters`, which also feeds SETUP > Connection) after the first item.
 */
internal enum class HelpTopic(
    @StringRes val titleRes: Int,
    val items: List<HelpItem>,
    val isCollapsible: Boolean = false,
    val showsAdapterList: Boolean = false,
) {
    CONNECT(
        R.string.help_topic_connect,
        listOf(
            HelpItem(R.string.help_connect_before_h, R.string.help_connect_before_b),
            HelpItem(R.string.help_connect_bt_h, R.string.help_connect_bt_b, SetupSection.Connection),
            HelpItem(R.string.help_connect_ble_h, R.string.help_connect_ble_b),
            HelpItem(R.string.help_connect_wifi_h, R.string.help_connect_wifi_b),
            HelpItem(R.string.help_connect_go_h, R.string.help_connect_go_b),
            HelpItem(R.string.help_connect_sim_h, R.string.help_connect_sim_b),
        ),
    ),
    TROUBLESHOOTING(
        R.string.help_topic_trouble,
        listOf(
            HelpItem(R.string.help_tr_missing_h, R.string.help_tr_missing_b, SetupSection.Connection),
            HelpItem(R.string.help_tr_nodata_h, R.string.help_tr_nodata_b, SetupSection.Debug),
            HelpItem(R.string.help_tr_unsupported_h, R.string.help_tr_unsupported_b),
            HelpItem(R.string.help_tr_slow_h, R.string.help_tr_slow_b),
            HelpItem(R.string.help_tr_headunit_h, R.string.help_tr_headunit_b, SetupSection.Startup),
            HelpItem(R.string.help_tr_gps_h, R.string.help_tr_gps_b, SetupSection.GpsTracks),
            HelpItem(R.string.help_tr_trip_h, R.string.help_tr_trip_b),
        ),
        isCollapsible = true,
    ),
    SUPPORTED_ADAPTERS(
        R.string.help_topic_adapters,
        listOf(
            HelpItem(null, R.string.help_ad_intro_b),
            HelpItem(R.string.help_ad_clones_h, R.string.help_ad_clones_b),
            HelpItem(R.string.help_ad_locked_h, R.string.help_ad_locked_b),
        ),
        showsAdapterList = true,
    ),
    LAP_TIMING(
        R.string.help_topic_laps,
        listOf(
            HelpItem(R.string.help_lap_gps_h, R.string.help_lap_gps_b, SetupSection.GpsTracks),
            HelpItem(R.string.help_lap_start_h, R.string.help_lap_start_b),
            HelpItem(R.string.help_lap_line_h, R.string.help_lap_line_b),
            HelpItem(R.string.help_lap_sector_h, R.string.help_lap_sector_b),
            HelpItem(R.string.help_lap_drive_h, R.string.help_lap_drive_b),
            HelpItem(R.string.help_lap_review_h, R.string.help_lap_review_b),
        ),
    ),
    EXPORT(
        R.string.help_topic_export,
        listOf(
            HelpItem(R.string.help_ex_how_h, R.string.help_ex_how_b),
            HelpItem(R.string.help_ex_programs_h, R.string.help_ex_programs_b),
            HelpItem(R.string.help_ex_transfer_h, R.string.help_ex_transfer_b),
            HelpItem(R.string.help_ex_units_h, R.string.help_ex_units_b),
        ),
    ),
    CAR_BATTERY(
        R.string.help_topic_battery,
        listOf(
            HelpItem(R.string.help_bat_on_h, R.string.help_bat_on_b),
            HelpItem(R.string.help_bat_do_h, R.string.help_bat_do_b),
            HelpItem(R.string.help_bat_app_h, R.string.help_bat_app_b, SetupSection.Warnings),
        ),
    ),
}
