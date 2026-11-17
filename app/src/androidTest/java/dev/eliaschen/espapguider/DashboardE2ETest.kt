package dev.eliaschen.espapguider

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eliaschen.espapguider.ble.WifiStatus
import dev.eliaschen.espapguider.device.ThermalSocket
import dev.eliaschen.espapguider.fake.FakeEsp32
import dev.eliaschen.espapguider.ui.dashboard.DashboardContent
import dev.eliaschen.espapguider.ui.theme.ESPAPGuiderTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End to end: real dashboard UI -> real ThermalSocket (OkHttp WebSocket) -> network -> FakeEsp32.
 * Bluetooth is replaced by handing the dashboard a Wi-Fi status directly.
 */
@RunWith(AndroidJUnit4::class)
class DashboardE2ETest {
    @get:Rule val rule = createComposeRule()
    private lateinit var esp: FakeEsp32

    @Before fun setUp() {
        esp = FakeEsp32().apply { start() }
    }

    @After fun tearDown() = esp.stop()

    private fun launch(status: WifiStatus = WifiStatus.Connected(esp.host), onProvision: (String, String) -> Unit = { _, _ -> }) {
        rule.setContent {
            ESPAPGuiderTheme {
                DashboardContent(
                    wifiStatus = status,
                    onProvision = onProvision,
                    onBleLed = {},
                    socket = ThermalSocket(esp.port),
                )
            }
        }
    }

    // ---- helpers

    private fun node(tag: String): SemanticsNodeInteraction {
        rule.onNodeWithTag("dashboard").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag, useUnmergedTree = true)
    }

    /** All text in the tagged node and its children (a tag on a container doesn't hold the Text itself). */
    private fun text(tag: String): String {
        fun collect(n: SemanticsNode): String =
            (n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: "") +
                n.children.joinToString("") { collect(it) }
        return collect(node(tag).fetchSemanticsNode())
    }

    private fun waitFor(what: String, timeoutMs: Long = 5_000, cond: () -> Boolean) {
        try {
            rule.waitUntil(timeoutMs) { runCatching(cond).getOrDefault(false) }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun waitForCommand(cmd: String) = waitFor("app to send \"$cmd\" (got ${esp.commands})") { cmd in esp.commands }

    private fun setSlider(tag: String, value: Float) =
        node(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    /** Scrolls [tag] into view with its card header clear of the top app bar, then takes a screenshot. */
    private fun screenshotAt(tag: String, name: String) {
        node(tag)
        val back = with(rule.density) { 96.dp.toPx() }
        runCatching {
            rule.onNodeWithTag("dashboard").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -back) }
        }
        rule.waitForIdle()
        screenshot(name)
    }

    private fun screenshot(name: String) = saveScreenshot(rule, name)


    private fun goLive() {
        launch()
        waitFor("WebSocket to go live") { text("link").startsWith("Live") }
    }

    // ---- tests

    @Test fun cameraStreamsAndHotObjectDrivesTheMatchingMotor() {
        goLive()
        node("thermal-image").assertIsDisplayed()
        assertEquals("0", text("duty-left"))

        esp.hotSide = 0
        waitFor("left motor to vibrate") { text("duty-left") != "0" }
        assertEquals("right motor stays idle", "0", text("duty-right"))
        assertTrue("left half sees hot pixels", text("hot-left").toInt() > 0)
        rule.onNodeWithText("Vibrating").assertIsDisplayed()
        waitFor("FPS counter") { !text("link").contains(" 0.0 FPS") }
        screenshotAt("link", "overview")            // connection + camera
        screenshotAt("mode-auto", "motors_auto")

        esp.hotSide = 1
        waitFor("right motor to take over") { text("duty-right") != "0" && text("duty-left") == "0" }
        screenshotAt("link", "camera_hot_right")

        esp.hotSide = null
        waitFor("both motors to stop") { text("duty-left") == "0" && text("duty-right") == "0" }
    }

    @Test fun triggerSliderChangesWhatCountsAsHot() {
        goLive()
        esp.hotSide = 0
        waitFor("left motor to vibrate at the default 50 °C trigger") { text("duty-left") != "0" }

        setSlider("trigger", 100f)             // the 80 °C object is now below the trigger
        waitFor("device to apply the 100 °C trigger") { esp.trigger == 100f }
        assertTrue(esp.commands.any { it == "thr:100.0" })
        waitFor("left motor to stop") { text("duty-left") == "0" }
    }

    @Test fun enableSwitchAndTestBuzz() {
        goLive()
        esp.hotSide = 0
        waitFor("left motor to vibrate") { text("duty-left") != "0" }

        node("enable-left").performClick()
        waitForCommand("en:0:0")
        waitFor("disabled motor to stop even though its half is hot") { text("duty-left") == "0" }
        rule.onNodeWithText("Disabled · hot").assertIsDisplayed()

        node("test-right").performClick()
        waitForCommand("test:1")
        waitFor("test buzz to show full power") { text("duty-right") == "100" }
        waitFor("test buzz to end") { text("duty-right") == "0" }
    }

    @Test fun manualModeControlsMotorsFromThePhone() {
        goLive()
        node("mode-manual").performClick()
        waitForCommand("mode:manual")
        waitFor("manual slider to appear") { node("manual-left").fetchSemanticsNode() != null }

        setSlider("manual-left", 75f)
        waitForCommand("vib:0:191")
        waitFor("left motor at 75 %") { text("duty-left") == "75" }
        assertEquals("right motor untouched", "0", text("duty-right"))

        esp.hotSide = 1                        // camera must not override manual control
        Thread.sleep(300)
        assertEquals("0", text("duty-right"))
        screenshotAt("mode-manual", "motors_manual")

        rule.onNodeWithText("Stop").performClick()
        waitFor("Stop to zero the motors") { text("duty-left") == "0" }

        node("mode-auto").performClick()
        waitForCommand("mode:auto")
        waitFor("camera to drive the right motor again") { text("duty-right") != "0" }
    }

    @Test fun physicalButtonPressesReachThePhone() {
        goLive()
        assertEquals("OFF", text("button-state"))
        assertEquals("0 presses", text("button-count"))

        esp.pressButton()
        waitFor("button to show pressed") { text("button-state") == "ON" }
        screenshotAt("button-count", "button_pressed")
        Thread.sleep(250)
        esp.releaseButton()
        waitFor("button to show released") { text("button-state") == "OFF" }
        rule.onNode(hasText("held", substring = true)).assertIsDisplayed()

        // A tap shorter than one frame is still counted (the firmware counts presses).
        esp.pressButton(); esp.releaseButton()
        waitFor("second press counted") { text("button-count") == "2 presses" }
    }

    @Test fun ledSwitchGoesOverWifi() {
        goLive()
        node("led").performClick()
        waitForCommand("on")
        waitFor("device LED on") { esp.led }
    }

    @Test fun reconnectsAfterTheLinkDrops() {
        goLive()
        esp.dropClients()
        waitFor("app to notice the drop") { !text("link").startsWith("Live") }
        waitFor("app to reconnect", timeoutMs = 10_000) { text("link").startsWith("Live") }
        esp.hotSide = 0
        waitFor("frames flowing again after reconnect") { text("duty-left") != "0" }
    }

    @Test fun missingCameraIsReportedButControlsStillWork() {
        esp.cameraOk = false
        goLive()
        rule.onNode(hasText("MLX90640 not found", substring = true)).assertIsDisplayed()
        node("test-left").performClick()
        waitFor("test buzz works without the camera") { text("duty-left") == "100" }
    }

    @Test fun wifiFormSendsCredentials() {
        var sent: Pair<String, String>? = null
        launch(WifiStatus.Disconnected) { s, p -> sent = s to p }
        rule.onNodeWithText("Wi-Fi name").performTextInput("lab-wifi")
        rule.onNodeWithText("Password").performTextInput("s3cret")
        rule.waitForIdle()
        screenshot("wifi_setup")
        rule.onNodeWithText("Send to ESP32").performClick()
        rule.waitForIdle()
        assertEquals("lab-wifi" to "s3cret", sent)
    }
}
