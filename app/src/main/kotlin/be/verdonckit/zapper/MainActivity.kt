package be.verdonckit.zapper

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import be.verdonckit.zapper.core.IdleController
import be.verdonckit.zapper.ui.AppNav
import be.verdonckit.zapper.ui.theme.ZapperTheme

class MainActivity : ComponentActivity() {

    private val idle by lazy {
        IdleController(this, screensaverAfterMs = { IdleController.NAVIGATION_SCREENSAVER_MS })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZapperTheme { AppNav() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = idle.handleKey(event) || super.dispatchKeyEvent(event)

    override fun onResume() {
        super.onResume()
        idle.start()
    }

    override fun onPause() {
        idle.stop()
        super.onPause()
    }
}
