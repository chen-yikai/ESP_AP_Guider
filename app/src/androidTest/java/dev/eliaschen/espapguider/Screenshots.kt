package dev.eliaschen.espapguider

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves the current screen to <app external files>/e2e/<name>.png (used for the README images).
 * Pull with: adb pull /sdcard/Android/data/dev.eliaschen.espapguider/files/e2e
 */
fun saveScreenshot(rule: ComposeContentTestRule, name: String) {
    val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    val dir = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use {
        rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
