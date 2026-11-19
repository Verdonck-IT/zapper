package be.verdonckit.zapper

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import be.verdonckit.zapper.ui.theme.ZapperColors
import be.verdonckit.zapper.core.IdleController
import be.verdonckit.zapper.ui.AppNav
import be.verdonckit.zapper.ui.theme.ZapperTheme

class MainActivity : ComponentActivity() {

    private val idle by lazy {
        IdleController(this, screensaverAfterMs = { IdleController.NAVIGATION_SCREENSAVER_MS })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge volgens developer.android.com: transparante balken met lichte iconen (donkere app);
        // de inhoud blijft binnen WindowInsets.safeDrawing (hieronder). Vanaf targetSdk 36 is dit verplicht.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // Tablet/gsm: niets onder de statusbalk of (Samsung-)taakbalk; de achtergrond loopt wel door.
            // Op TV zijn die marges 0.
            ZapperTheme {
                Box(Modifier.fillMaxSize().background(ZapperColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    AppNav()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = idle.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = idle.handleTouch(event) || super.dispatchTouchEvent(event)

    override fun onResume() {
        super.onResume()
        idle.start()
    }

    override fun onPause() {
        idle.stop()
        super.onPause()
    }
}
