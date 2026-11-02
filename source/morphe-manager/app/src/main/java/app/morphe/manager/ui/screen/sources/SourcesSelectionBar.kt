/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.bundles.PatchBundleSource
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.hasPrereleaseChannel
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.isDefault
import app.morphe.manager.domain.bundles.PatchBundleSource.Extensions.usesPrerelease
import app.morphe.manager.domain.bundles.RemotePatchBundle
import app.morphe.manager.ui.screen.shared.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

/**
 * The panel a selection of sources docks under the page, as the home screen's does for apps: what
 * can be done to every source picked at once, and the reorder it leads to.
 *
 * Each action reaches only the picked sources it applies to, so a selection mixing them still
 * offers it: a blocked source sits out updating and switching, and the default one deleting.
 * A setting a source card offers only for some sources is offered here once any source has it.
 *
 * @param sources Every source, which the picked uids are looked up in.
 * @param listed The sources on screen, which "select all" picks.
 * @param blocked Uids of the sources the blocklist names.
 * @param withExperimentalVersions Uids of the sources declaring experimental app versions.
 * @param experimentalVersionsEnabled Uids of the sources with experimental app versions switched on.
 * @param onStartReorder Leaves the selection for the reorder, or null while the list's order is not
 *   the user's own to drag. The page header carries the reorder from there.
 */
@Composable
internal fun SourcesSelectionBar(
    state: SourcesPageState,
    sources: List<PatchBundleSource>,
    listed: List<PatchBundleSource>,
    blocked: Set<Int>,
    withExperimentalVersions: Set<Int>,
    experimentalVersionsEnabled: Set<String>,
    onUpdate: (List<PatchBundleSource>) -> Unit,
    onToggleEnabled: (List<PatchBundleSource>) -> Unit,
    onSetPrerelease: (List<PatchBundleSource>, Boolean) -> Unit,
    onSetExperimentalVersions: (List<PatchBundleSource>, Boolean) -> Unit,
    onDelete: (List<PatchBundleSource>) -> Unit,
    onStartReorder: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val selection = state.selection
    val selected = sources.filter { selection.contains(it.uid) }
    val switchable = selected.filter { it.uid !in blocked }
    val updatable = switchable.filterIsInstance<RemotePatchBundle>()
    // A selection holding any source that is on switches them off, so one tap never flips the rest on
    val toDisable = switchable.filter { it.enabled }
    val deletable = selected.filterNot { it.isDefault }

    val updateLabel = stringResource(R.string.update)
    val disableLabel = stringResource(R.string.disable)
    val enableLabel = stringResource(R.string.enable)
    val prereleaseEnableLabel = stringResource(R.string.sources_management_prerelease_enable)
    val prereleaseDisableLabel = stringResource(R.string.sources_management_prerelease_disable)
    val experimentalEnableLabel = stringResource(R.string.sources_management_experimental_versions_enable)
    val experimentalDisableLabel = stringResource(R.string.sources_management_experimental_versions_disable)
    val deleteLabel = stringResource(R.string.delete)

    // Every action but deleting and confirming pre-releases is done at once, which the
    // selection then gives way to
    fun acting(action: () -> Unit): () -> Unit = {
        action()
        state.exitSelection()
    }

    // A setting of the picked sources it applies to, switched on for those it is off on and off
    // for those it is on, so a mixed selection offers both and either reaches all of it in one tap
    fun MutableList<SelectionAction>.addSettingSwitch(
        applicable: List<PatchBundleSource>,
        isOn: (PatchBundleSource) -> Boolean,
        enableLabel: String,
        disableLabel: String,
        icon: ImageVector,
        exitsOnEnable: Boolean = true,
        onSet: (List<PatchBundleSource>, Boolean) -> Unit
    ) {
        val (on, off) = applicable.partition(isOn)
        // Held while nothing picked takes the setting, so the panel keeps its rows
        if (off.isNotEmpty() || on.isEmpty()) {
            val enable = { onSet(off, true) }
            add(
                SelectionAction(
                    icon = icon,
                    label = enableLabel,
                    onClick = if (exitsOnEnable) acting(enable) else enable,
                    enabled = off.isNotEmpty()
                )
            )
        }
        if (on.isNotEmpty()) {
            add(SelectionAction(icon, disableLabel, acting { onSet(on, false) }))
        }
    }

    val actions = buildList {
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Refresh,
                label = updateLabel,
                onClick = acting { onUpdate(updatable) },
                enabled = updatable.isNotEmpty(),
                tone = ActionTone.Primary
            )
        )
        add(
            if (toDisable.isNotEmpty()) {
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Block,
                    label = disableLabel,
                    onClick = acting { onToggleEnabled(toDisable) }
                )
            } else {
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Check_circle,
                    label = enableLabel,
                    onClick = acting { onToggleEnabled(switchable) },
                    enabled = switchable.isNotEmpty()
                )
            }
        )
        if (sources.any { it.hasPrereleaseChannel }) {
            addSettingSwitch(
                applicable = switchable.filter { it.hasPrereleaseChannel },
                isOn = { it.usesPrerelease },
                enableLabel = prereleaseEnableLabel,
                disableLabel = prereleaseDisableLabel,
                icon = MaterialSymbols.Rounded.Rocket_launch,
                // Waits on the confirmation, which the selection gives way to instead
                exitsOnEnable = false,
                onSet = onSetPrerelease
            )
        }
        if (sources.any { it.offersExperimentalVersions(withExperimentalVersions) }) {
            addSettingSwitch(
                applicable = selected.filter { it.offersExperimentalVersions(withExperimentalVersions) },
                isOn = { it.uid.toString() in experimentalVersionsEnabled },
                enableLabel = experimentalEnableLabel,
                disableLabel = experimentalDisableLabel,
                // The mark an experimental version carries wherever versions are listed
                icon = MaterialSymbols.Rounded.Science,
                onSet = onSetExperimentalVersions
            )
        }
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Delete,
                label = deleteLabel,
                onClick = { onDelete(deletable) },
                enabled = deletable.isNotEmpty(),
                tone = ActionTone.Destructive
            )
        )
    }

    MultiSelectBar(
        selectedCount = selection.size,
        totalCount = listed.size,
        visible = state.isSelecting,
        onSelectAll = { selection.setAll(listed.map { it.uid }) },
        onDeselectAll = selection::clear,
        onCancel = state::exitSelection,
        actions = actions,
        onReorder = onStartReorder,
        modifier = modifier
    )
}
