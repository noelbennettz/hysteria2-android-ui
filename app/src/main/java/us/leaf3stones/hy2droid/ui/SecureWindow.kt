package us.leaf3stones.hy2droid.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Keeps everything on this screen out of screenshots, screen recordings and the
 * recents thumbnail for as long as the composable is on screen.
 *
 * Used wherever a secret is visible: the share dialog (QR + link) and the profile
 * editor (auth / obfs password). A Compose dialog lives in its own window, so the
 * flag is applied to the dialog window *and* the activity window; both are cleared
 * again on dispose, leaving the rest of the app screenshot-able.
 */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val windows = listOfNotNull(
            (view.parent as? DialogWindowProvider)?.window,
            context.findActivity()?.window,
        ).distinct()
        windows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        onDispose { windows.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
    }
}

/** Walk the context chain so a Compose `LocalContext` can reach its Activity. */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/**
 * Put [text] on the system clipboard, marked sensitive where the platform
 * supports it (Android 13+): the system hides the preview, keeps it out of
 * clipboard-history UIs and clears it sooner. Share links carry the secret.
 */
internal fun Context.copySensitive(text: String) {
    val clip = ClipData.newPlainText("hysteria2", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
