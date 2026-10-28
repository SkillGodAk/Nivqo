/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.util.requestIgnoreBatteryOptimizations
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * The battery optimization exemption that keeps the patcher running while the manager is out of
 * sight, a row of the group it is placed in. Its state is read again each time the screen comes
 * back, as it is changed in the system's own settings.
 */
@Composable
fun BatteryOptimizationItem() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isIgnoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    SettingsItem(
        onClick = {
            context.requestIgnoreBatteryOptimizations()
        },
        title = stringResource(R.string.settings_system_battery_optimization),
        subtitle = stringResource(R.string.settings_system_battery_optimization_description),
        leadingContent = { ThemedIcon(icon = MaterialSymbols.Rounded.Battery_android_frame_plus) },
        statusContent = {
            StatusCircleIcon(
                icon = if (isIgnoringBatteryOptimizations) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.Warning,
                containerColor = if (isIgnoringBatteryOptimizations) MaterialTheme.colorScheme.primaryContainer
                else SemanticTone.Warning.container,
                contentColor = if (isIgnoringBatteryOptimizations) MaterialTheme.colorScheme.onPrimaryContainer
                else SemanticTone.Warning.content
            )
        }
    )
}
