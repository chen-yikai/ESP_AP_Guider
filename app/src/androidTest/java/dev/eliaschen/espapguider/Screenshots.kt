package dev.eliaschen.espapguider

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves a screenshot to <app external files>/e2e/<name>.png (used for the README images).
 * Pull with: adb pull /sdcard/Android/data/dev.eliaschen.espapguider/files/e2e
 *
 * [fullScreen] = false captures just the app window (clean, no system bars).
 * [fullScreen] = true captures the whole display — needed for dialogs, which live in their own window.
 */
fun saveScreenshot(rule: ComposeContentTestRule, name: String, fullScreen: Boolean = false) {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }
    rule.waitForIdle()
    val shot: Bitmap =
        if (fullScreen) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        else rule.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
    File(dir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
