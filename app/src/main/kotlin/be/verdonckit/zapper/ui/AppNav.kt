package be.verdonckit.zapper.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import be.verdonckit.zapper.data.ItemEntity
import be.verdonckit.zapper.player.PlayerActivity
import be.verdonckit.zapper.ui.detail.DetailScreen
import be.verdonckit.zapper.ui.home.HomeScreen
import be.verdonckit.zapper.ui.settings.SettingsScreen
import be.verdonckit.zapper.ui.theme.ZapperColors

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val context = LocalContext.current

    fun open(item: ItemEntity) {
        val playable = item.playableId
        if (playable != null && item.kind == "LIVE") {
            context.startActivity(PlayerActivity.intent(context, item.providerId, playable, item.title, item.key, item.backdropUrl))
        } else {
            nav.navigate("detail/${Uri.encode(item.key)}")
        }
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        modifier = Modifier.fillMaxSize().background(ZapperColors.Background),
    ) {
        composable("home") {
            HomeScreen(onOpen = ::open, onSettings = { nav.navigate("settings") })
        }
        composable("detail/{key}") { entry ->
            DetailScreen(itemKey = Uri.decode(entry.arguments?.getString("key").orEmpty()))
        }
        composable("settings") { SettingsScreen() }
    }
}
