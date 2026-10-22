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
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.ui.components.SidebarDestination
import be.verdonckit.zapper.ui.detail.DetailScreen
import be.verdonckit.zapper.ui.live.LiveScreen
import be.verdonckit.zapper.ui.mylist.MyListScreen
import be.verdonckit.zapper.ui.detail.EpisodeScreen
import be.verdonckit.zapper.ui.home.HomeScreen
import be.verdonckit.zapper.ui.search.SearchScreen
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
            HomeScreen(
                onOpen = ::open,
                onNavigate = { dest ->
                    when (dest) {
                        SidebarDestination.HOME -> Unit
                        SidebarDestination.SEARCH -> nav.navigate("search")
                        SidebarDestination.LIVE -> nav.navigate("live")
                        SidebarDestination.MY_LIST -> nav.navigate("mylist")
                        SidebarDestination.SETTINGS -> nav.navigate("settings")
                    }
                },
            )
        }
        composable("detail/{key}") { entry ->
            DetailScreen(
                itemKey = Uri.decode(entry.arguments?.getString("key").orEmpty()),
                onOpenEpisode = { itemKey, episode ->
                    context.graph.episodeSelection["$itemKey|${episode.playableId}"] = episode
                    nav.navigate("episode/${Uri.encode(itemKey)}/${Uri.encode(episode.playableId)}")
                },
            )
        }
        composable("episode/{key}/{playable}") { entry ->
            EpisodeScreen(
                itemKey = Uri.decode(entry.arguments?.getString("key").orEmpty()),
                playableId = Uri.decode(entry.arguments?.getString("playable").orEmpty()),
                onGone = { nav.popBackStack() },
            )
        }
        composable("live") { LiveScreen(onBack = { nav.popBackStack() }) }
        composable("mylist") { MyListScreen(onOpen = ::open, onBack = { nav.popBackStack() }) }
        composable("search") { SearchScreen(onOpen = ::open, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
