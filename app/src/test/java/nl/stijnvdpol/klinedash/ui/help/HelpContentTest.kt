package nl.stijnvdpol.klinedash.ui.help

import nl.stijnvdpol.klinedash.ui.SetupSection
import nl.stijnvdpol.klinedash.ui.SupportedAdapters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelpContentTest {
    private val strings: Map<String, String> = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""")
        .findAll(File("src/main/res/values/strings.xml").readText())
        .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&") }

    private fun text(name: String): String = checkNotNull(strings[name]) { "No string $name" }

    @Test
    fun `the help has the six topics, troubleshooting second`() {
        assertEquals(6, HelpTopic.entries.size)
        assertEquals(HelpTopic.CONNECT, HelpTopic.entries[0])
        assertEquals(HelpTopic.TROUBLESHOOTING, HelpTopic.entries[1])
        HelpTopic.entries.forEach { assertTrue("$it", it.items.isNotEmpty()) }
    }

    @Test
    fun `troubleshooting answers the nine questions drivers ask, each collapsible`() {
        val topic = HelpTopic.TROUBLESHOOTING
        assertTrue(topic.isCollapsible)
        assertEquals(9, topic.items.size)
        assertTrue(topic.items.all { it.heading != null })
        assertEquals(1, HelpTopic.entries.count { it.isCollapsible })
    }

    @Test
    fun `the make and red line questions link to the Car page, where both are set`() {
        val car = HelpTopic.TROUBLESHOOTING.items.filter {
            it.body == nl.stijnvdpol.klinedash.R.string.help_tr_make_b || it.body == nl.stijnvdpol.klinedash.R.string.help_tr_redline_b
        }
        assertEquals(2, car.size)
        assertTrue(car.all { it.link == SetupSection.Car })
    }

    @Test
    fun `the head unit question links to Startup, where the whitelist is explained`() {
        val headUnit = HelpTopic.TROUBLESHOOTING.items.first { it.body == nl.stijnvdpol.klinedash.R.string.help_tr_headunit_b }
        assertEquals(SetupSection.Startup, headUnit.link)
    }

    @Test
    fun `the adapters topic shows the one list of adapters that work`() {
        assertTrue(HelpTopic.SUPPORTED_ADAPTERS.showsAdapterList)
        assertEquals(1, HelpTopic.entries.count { it.showsAdapterList })
        assertTrue(SupportedAdapters.isNotEmpty())
    }

    @Test
    fun `the adapters named as not working are named in the README too`() {
        val readme = File("../README.md").readText().replace("`", "").replace("*", "").replace(Regex("""\s+"""), " ")
        listOf("BlueDriver", "ThinkDiag", "Golo", "EasyDiag", "TopScan", "ANCEL BD200", "Autel MaxiAP").forEach { name ->
            assertTrue("The help names $name", text("help_ad_locked_b").contains(name))
            assertTrue("The README does not mention $name", readme.contains(name))
        }
    }

    @Test
    fun `help texts that name a button use the words the button has`() {
        val references = mapOf(
            "help_tr_nodata_b" to listOf("btn_forget_protocol"),
            "help_tr_missing_b" to listOf("btn_refresh", "ble_scan"),
            "help_tr_gps_b" to listOf("gps_allow"),
            "help_connect_go_b" to listOf("btn_connect"),
            "help_lap_start_b" to listOf("session_start_laps", "session_start_timing"),
            "help_lap_line_b" to listOf("session_set_start_finish"),
            "help_lap_sector_b" to listOf("session_add_sector"),
            "help_ex_how_b" to listOf("detail_export"),
            "help_ex_transfer_b" to listOf("trips_copy", "trips_wifi_start"),
            "fail_permission_body" to listOf("fix_allow_bluetooth"),
            "fail_not_found_ble_body" to listOf("ble_scan"),
            "wiz_ble_step_scan" to listOf("ble_scan"),
        )
        references.forEach { (helpName, labels) ->
            labels.forEach { label ->
                assertTrue("$helpName should say \"${text(label)}\"", text(helpName).contains(text(label)))
            }
        }
        assertTrue(text("help_tr_trip_b").contains(text("trips_auto_moving").substringBefore(" (")))
    }

    @Test
    fun `the help says exports are metric, as the formats expect`() {
        val units = text("help_ex_units_b")
        assertTrue(units.contains("metric"))
        assertTrue(units.contains("VBO") && units.contains("GPX"))
        assertTrue(units.contains("CSV"))
    }

    @Test
    fun `the privacy statement says what the welcome screen promises`() {
        val privacy = text("about_privacy_body")
        assertTrue(privacy.contains("offline"))
        assertTrue(privacy.contains("no account"))
        assertTrue(privacy.contains("no ads"))
        assertTrue(privacy.contains("analytics"))
        assertTrue(text("wiz_welcome_privacy").contains("Works offline. No account."))
        assertFalse(privacy.contains("cloud", ignoreCase = true))
    }
}
