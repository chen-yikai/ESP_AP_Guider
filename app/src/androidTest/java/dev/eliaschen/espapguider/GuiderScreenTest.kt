package dev.eliaschen.espapguider

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eliaschen.espapguider.ble.BLTService
import dev.eliaschen.espapguider.ble.LocalBLT
import dev.eliaschen.espapguider.ui.guider.Guider
import dev.eliaschen.espapguider.ui.theme.ESPAPGuiderTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first screen: scanning for the ESP32 over BLE (no scan is started here). */
@RunWith(AndroidJUnit4::class)
class GuiderScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsScanningState() {
        val ble = BLTService(InstrumentationRegistry.getInstrumentation().targetContext)
        rule.setContent {
            ESPAPGuiderTheme {
                CompositionLocalProvider(LocalBLT provides ble) { Guider() }
            }
        }
        // The loading indicator animates forever; drive the clock by hand so the test can go idle.
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText("Scanning for", substring = true).assertIsDisplayed()
        saveScreenshot(rule, "guide")
    }
}
