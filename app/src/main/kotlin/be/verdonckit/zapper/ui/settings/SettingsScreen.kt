package be.verdonckit.zapper.ui.settings

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import be.verdonckit.zapper.ui.components.BackToTop
import be.verdonckit.zapper.ui.components.requestFocusAfterLayout
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import be.verdonckit.zapper.api.LoginMethod
import be.verdonckit.zapper.graph
import be.verdonckit.zapper.plugin.Compatibility
import be.verdonckit.zapper.update.AppUpdater
import kotlinx.coroutines.delay
import be.verdonckit.zapper.ui.theme.ZapperColors

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel {
        SettingsViewModel(context.applicationContext as Application, context.graph)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var loginFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val firstButton = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    // Verplichte app-update met een installeerbare versie: die knop krijgt de eerste focus.
    val compat = state.compatibility
    val installFirst = compat?.updateRequired == true && state.appUpdate != null
    val firstModifier = Modifier.focusRequester(firstButton).onFocusChanged { firstFocused = it.isFocused }

    // Terug: eerst naar boven (eerste knop), dan pas naar het startscherm.
    BackToTop(
        atTop = { firstFocused },
        toTop = {
            listState.scrollToItem(0)
            firstButton.requestFocusAfterLayout()
        },
        onBackAtTop = onBack,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(56.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text("Instellingen", style = MaterialTheme.typography.displaySmall, color = Color.White)
        }
        if (compat != null && (compat.updateRequired || compat.awaitingPlugins.isNotEmpty())) {
            item {
                CompatibilityNotice(
                    compat = compat,
                    appUpdate = state.appUpdate,
                    appUpdateTooOld = state.appUpdateTooOld,
                    busy = state.busy,
                    onInstall = vm::checkAppUpdate,
                    buttonModifier = if (installFirst) firstModifier else Modifier,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = vm::syncNow,
                    enabled = !state.busy,
                    modifier = if (installFirst) Modifier else firstModifier,
                ) { Text("Catalogus nu syncen") }
                OutlinedButton(onClick = vm::updatePlugins, enabled = !state.busy) { Text("Plugins bijwerken") }
                OutlinedButton(onClick = vm::checkAppUpdate, enabled = !state.busy) { Text("App-update zoeken") }
            }
        }
        state.message?.let { msg ->
            item {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZapperColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ZapperColors.Surface, RoundedCornerShape(6.dp))
                        .padding(16.dp),
                )
            }
        }
        if (state.providers.isEmpty()) {
            item {
                Text(
                    "Nog geen plugins geladen. Kies 'Plugins bijwerken' om ze te downloaden.",
                    color = ZapperColors.TextSecondary,
                )
            }
        }
        items(state.providers, key = { it.id }) { p ->
            ProviderCard(
                status = p,
                showLogin = loginFor == p.id,
                deviceLogin = state.deviceLogin?.takeIf { it.providerId == p.id },
                busy = state.busy,
                onLoginClick = {
                    if (p.loginMethod == LoginMethod.DEVICE_CODE) vm.startDeviceLogin(p.id)
                    else loginFor = if (loginFor == p.id) null else p.id
                },
                onCancelDeviceLogin = vm::cancelDeviceLogin,
                onLogin = { u, pw -> vm.login(p.id, u, pw); loginFor = null },
                onLogout = { vm.logout(p.id) },
                onTest = { vm.healthCheck(p.id) },
            )
        }
    }
}

@Composable
private fun ProviderCard(
    status: ProviderStatus,
    showLogin: Boolean,
    deviceLogin: DeviceLoginUi?,
    busy: Boolean,
    onLoginClick: () -> Unit,
    onCancelDeviceLogin: () -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ZapperColors.Surface, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(status.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("plugin ${status.version}", style = MaterialTheme.typography.bodyMedium, color = ZapperColors.TextSecondary)
        }
        val loginText = when {
            !status.requiresLogin -> "Geen login nodig"
            status.loggedIn == true -> "Ingelogd${status.username?.let { " als $it" } ?: ""}"
            status.loggedIn == false -> "Niet ingelogd"
            status.offline -> "Tijdelijk niet bereikbaar (offline of login even gepauzeerd)"
            else -> "Status onbekend"
        }
        Text(loginText, color = if (status.loggedIn == true) ZapperColors.Success else ZapperColors.TextSecondary)
        status.health?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ZapperColors.TextSecondary) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.requiresLogin) {
                Button(onClick = onLoginClick, enabled = !busy && deviceLogin == null) {
                    Text(
                        when {
                            status.loggedIn == true -> "Opnieuw inloggen"
                            status.loginMethod == LoginMethod.DEVICE_CODE -> "Inloggen met code"
                            else -> "Inloggen"
                        },
                    )
                }
                if (status.loggedIn == true) OutlinedButton(onClick = onLogout, enabled = !busy) { Text("Uitloggen") }
            }
            OutlinedButton(onClick = onTest, enabled = !busy) { Text("Test plugin") }
        }

        if (showLogin) LoginForm(initialUser = status.username.orEmpty(), onSubmit = onLogin)
        deviceLogin?.let { DeviceLoginPanel(it, onCancelDeviceLogin) }
    }
}

/**
 * Verplichte app-update (plugins vragen een nieuwere plugin-API) of plugins die nog niet gepubliceerd
 * zijn voor deze app-versie. Met een installeerbare versie: knop om meteen bij te werken.
 */
@Composable
private fun CompatibilityNotice(
    compat: Compatibility,
    appUpdate: AppUpdater.Available?,
    appUpdateTooOld: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    buttonModifier: Modifier,
) {
    val color = if (compat.updateRequired) ZapperColors.Warning else ZapperColors.TextSecondary
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(ZapperColors.Surface, RoundedCornerShape(6.dp))
            .border(BorderStroke(2.dp, color), RoundedCornerShape(6.dp))
            .padding(20.dp),
    ) {
        if (compat.updateRequired) {
            Text("⚠  App-update verplicht", style = MaterialTheme.typography.headlineSmall, color = color)
            Text(
                "${compat.needsNewerApp.joinToString()} ${if (compat.needsNewerApp.size == 1) "vraagt" else "vragen"} een nieuwere " +
                    "versie van Zapper (plugin-API ${compat.requiredApi}, deze app heeft ${compat.appApi}). " +
                    "Tot je de app bijwerkt, krijgen die zenders geen updates of fixes meer.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            when {
                appUpdate != null -> Button(onClick = onInstall, enabled = !busy, modifier = buttonModifier) {
                    Text("Zapper ${appUpdate.versionName} installeren")
                }
                appUpdateTooOld -> Text(
                    "De nieuwste gepubliceerde app-versie ondersteunt plugin-API ${compat.requiredApi} nog niet. Probeer later opnieuw.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZapperColors.TextSecondary,
                )
                else -> Text(
                    "Nog geen nieuwere app-versie gevonden. Kies later 'App-update zoeken'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZapperColors.TextSecondary,
                )
            }
        }
        if (compat.awaitingPlugins.isNotEmpty()) {
            Text(
                "Nog geen plugin voor deze app-versie: ${compat.awaitingPlugins.joinToString()}. " +
                    "Ze verschijnen zodra ze gepubliceerd zijn.",
                style = MaterialTheme.typography.bodyMedium,
                color = ZapperColors.TextSecondary,
            )
        }
    }
}

/** Login met code: grote code en de URL, zoals de eigen TV-app van het platform. */
@Composable
private fun DeviceLoginPanel(login: DeviceLoginUi, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(login) {
        while (true) {
            remaining = ((login.expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }
    LaunchedEffect(login) { cancel.requestFocusAfterLayout() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ga op je gsm of computer naar", style = MaterialTheme.typography.bodyLarge, color = ZapperColors.TextSecondary)
        Text(login.verificationUrl.removePrefix("https://"), style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Text("en geef deze code in:", style = MaterialTheme.typography.bodyLarge, color = ZapperColors.TextSecondary)
        Text(login.userCode, style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 8.sp), color = Color.White)
        Text(
            "Wachten op bevestiging… nog ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}",
            style = MaterialTheme.typography.bodyMedium,
            color = ZapperColors.TextSecondary,
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Annuleren") }
    }
}

@Composable
private fun LoginForm(initialUser: String, onSubmit: (String, String) -> Unit) {
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvTextField(user, { user = it }, "E-mailadres", KeyboardType.Email, password = false)
        TvTextField(pass, { pass = it }, "Wachtwoord", KeyboardType.Password, password = true)
        Button(onClick = { onSubmit(user, pass) }, enabled = user.isNotBlank() && pass.isNotBlank()) { Text("Inloggen") }
    }
}

/** tv-material heeft geen TextField; dit is een D-pad-vriendelijke variant die de TV-toetsenbord-IME opent. */
@Composable
private fun TvTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ZapperColors.TextSecondary)
        Box(
            Modifier
                .width(480.dp)
                .border(BorderStroke(2.dp, if (focused) Color.White else Color(0xFF555555)), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
