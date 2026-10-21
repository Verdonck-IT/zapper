package be.verdonckit.zapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import be.verdonckit.zapper.ui.AppNav
import be.verdonckit.zapper.ui.theme.ZapperTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZapperTheme { AppNav() }
        }
    }
}
