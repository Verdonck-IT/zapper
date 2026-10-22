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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import be.verdonckit.zapper.graph
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
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = vm::syncNow,
                    enabled = !state.busy,
                    modifier = Modifier.focusRequester(firstButton).onFocusChanged { firstFocused = it.isFocused },
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
                busy = state.busy,
                onLoginClick = { loginFor = if (loginFor == p.id) null else p.id },
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
    busy: Boolean,
    onLoginClick: () -> Unit,
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
            else -> "Status onbekend"
        }
        Text(loginText, color = if (status.loggedIn == true) ZapperColors.Success else ZapperColors.TextSecondary)
        status.health?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = ZapperColors.TextSecondary) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.requiresLogin) {
                Button(onClick = onLoginClick, enabled = !busy) { Text(if (status.loggedIn == true) "Opnieuw inloggen" else "Inloggen") }
                if (status.loggedIn == true) OutlinedButton(onClick = onLogout, enabled = !busy) { Text("Uitloggen") }
            }
            OutlinedButton(onClick = onTest, enabled = !busy) { Text("Test plugin") }
        }

        if (showLogin) LoginForm(initialUser = status.username.orEmpty(), onSubmit = onLogin)
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
