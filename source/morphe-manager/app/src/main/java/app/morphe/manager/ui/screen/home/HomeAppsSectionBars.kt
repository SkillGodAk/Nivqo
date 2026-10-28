/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.morphe.manager.R
import app.morphe.manager.domain.manager.HomeAppCategoryViewMode
import app.morphe.manager.domain.manager.HomeAppSortMode
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.screen.shared.ActionTone
import app.morphe.manager.ui.screen.shared.ListOptionSection
import app.morphe.manager.ui.screen.shared.ListOptionsPanel
import app.morphe.manager.ui.screen.shared.MultiSelectBar
import app.morphe.manager.ui.screen.shared.MultiSelectReorder
import app.morphe.manager.ui.screen.shared.SearchFieldState
import app.morphe.manager.ui.screen.shared.SelectionAction
import app.morphe.manager.ui.screen.shared.UserMessages
import app.morphe.manager.ui.screen.shared.autoMirrored
import app.morphe.manager.ui.screen.shared.listOptionSection
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Hands the apps section's footer bar to the page that docks it. The bar acts on what the section
 * holds, but docks to the bottom edge of the page over controls a selection has no use for, which
 * the section cannot reach from inside its own bounds.
 *
 * What passes is what the bar is drawn from rather than the bar itself: a value that changes with
 * the section, so the dock is drawn again whenever the section is, which a composable handed over
 * once and kept by the composition is not.
 */
@Stable
internal class HomeFooterBarHost {
    /** What the bar is drawn from, or null while no section is on screen. */
    var content by mutableStateOf<HomeFooterBarContent?>(null)
}

/** Everything [HomeAppsFooterBars] is drawn from, see there. */
internal data class HomeFooterBarContent(
    val state: HomeAppsSectionState,
    val apps: HomeAppListUi,
    val appActions: HomeAppActions,
    val search: SearchFieldState,
    val listedItems: List<HomeAppItem>,
    val reorderItems: List<HomeAppItem>,
    val orderedItems: List<HomeAppItem>,
    val itemsByPackage: Map<String, HomeAppItem>,
    val groupedSelectionGroup: HomeCategoryGroup?,
    val groupedSelectionPackages: Set<String>?,
    val sourceGroups: List<HomeCategoryGroup>,
    val isCustomCategoryView: Boolean,
    val isSourceCategoryView: Boolean,
    val listState: LazyListState,
    val leadingItemCount: Int,
    val scope: CoroutineScope
)

/** Draws the bar [host] carries, docked to the bottom of whatever [modifier] aligns it to. */
@Composable
internal fun HomeFooterBarDock(host: HomeFooterBarHost, modifier: Modifier = Modifier) {
    val content = host.content ?: return
    HomeAppsFooterBars(
        state = content.state,
        apps = content.apps,
        appActions = content.appActions,
        search = content.search,
        listedItems = content.listedItems,
        reorderItems = content.reorderItems,
        orderedItems = content.orderedItems,
        itemsByPackage = content.itemsByPackage,
        groupedSelectionGroup = content.groupedSelectionGroup,
        groupedSelectionPackages = content.groupedSelectionPackages,
        sourceGroups = content.sourceGroups,
        isCustomCategoryView = content.isCustomCategoryView,
        isSourceCategoryView = content.isSourceCategoryView,
        listState = content.listState,
        leadingItemCount = content.leadingItemCount,
        scope = content.scope,
        modifier = modifier
    )
}

/**
 * The bar that occupies the footer slot: app multi-select and reorder, the category context
 * actions, or the options the list is arranged by. Only one is ever visible - [HomeAppsSectionState]
 * keeps the modes exclusive - so they live together here, out of the section body.
 */
@Composable
private fun HomeAppsFooterBars(
    state: HomeAppsSectionState,
    apps: HomeAppListUi,
    appActions: HomeAppActions,
    search: SearchFieldState,
    listedItems: List<HomeAppItem>,
    reorderItems: List<HomeAppItem>,
    orderedItems: List<HomeAppItem>,
    itemsByPackage: Map<String, HomeAppItem>,
    groupedSelectionGroup: HomeCategoryGroup?,
    groupedSelectionPackages: Set<String>?,
    sourceGroups: List<HomeCategoryGroup>,
    isCustomCategoryView: Boolean,
    isSourceCategoryView: Boolean,
    listState: LazyListState,
    leadingItemCount: Int,
    scope: CoroutineScope,
    modifier: Modifier = Modifier
) {
    val homeAppItems = apps.visible
    val selectedPackages = state.selectedPackages

    // What "select all" covers: the scope being reordered, the group being selected in, or
    // whatever the search and filter left on screen when neither narrows it
    val activeAppScopePackages = state.reorderScopePackages ?: groupedSelectionPackages
    // Memoized - otherwise selection toggles refilter listedItems each pass
    val activeAppScopeItems = remember(
        activeAppScopePackages,
        groupedSelectionGroup,
        listedItems,
        reorderItems,
        state.isReorderMode
    ) {
        when {
            state.isReorderMode -> reorderItems
            groupedSelectionGroup != null -> groupedSelectionGroup.items
            activeAppScopePackages != null -> listedItems.filter { it.id in activeAppScopePackages }
            else -> listedItems
        }
    }
    val selectedAppItems = remember(selectedPackages.keys.toList(), homeAppItems) {
        val selected = selectedPackages.keys.toSet()
        homeAppItems.filter { it.id in selected }
    }
    val selectedInstalledItems = remember(selectedAppItems) {
        // Apps that are on the device but were never patched here have nothing Morphe can
        // uninstall, so they must not put the selection into the uninstall verb
        selectedAppItems.filter { it.isInstalledOnDevice && it.installedApp != null }
    }
    val selectedReinstallItems = remember(selectedAppItems) {
        selectedAppItems.filter {
            !it.isInstalledOnDevice && it.hasSavedCopy && it.installedApp != null
        }
    }
    // Reinstall and uninstall are offered only when they apply to the whole selection rather
    // than to some of it
    val contextActionIsReinstall = selectedAppItems.isNotEmpty() &&
            selectedReinstallItems.size == selectedAppItems.size
    val contextActionIsUninstall = selectedAppItems.isNotEmpty() &&
            selectedInstalledItems.size == selectedAppItems.size
    val messages = koinInject<UserMessages>()
    val reinstallLabel = stringResource(R.string.reinstall)
    val uninstallLabel = stringResource(R.string.uninstall)
    val patchSelectedLabel = stringResource(
        if (apps.batchQueuePending) R.string.batch_queue_add_action else R.string.batch_patch_action
    )
    val patchSourcesLabel = stringResource(R.string.sources_management_title)
    val moveToCategoryLabel = stringResource(R.string.home_category_move_to)
    val reorderListLabel = stringResource(R.string.reorder_list)
    val hideLabel = stringResource(R.string.hide)
    val hiddenMessage = appsHiddenMessage(selectedPackages.keys.mapNotNull(itemsByPackage::get))

    val enterReorder: () -> Unit = {
        groupedSelectionPackages?.let { pkgs ->
            selectedPackages.retain { it in pkgs }
        }
        state.reorderScopePackages = groupedSelectionPackages
        state.reorderScopeSourceUid = groupedSelectionGroup?.sourceUid
        val sourceOrder = groupedSelectionGroup
            ?.takeIf { it.sourceUid != null }
            ?.items
            ?.map { it.id }
        state.scopedSourceOrder = sourceOrder
        val focusTargets = selectedPackages.keys.toSet()
        // Grouped pre-scrolls below (before flipping mode) so the LazyColumn doesn't hold a
        // stale offset when items swap to the scoped list; flat defers to the LaunchedEffect
        // after flipping
        state.reorderFocusPackages = if (groupedSelectionPackages == null) focusTargets else emptySet()
        // A lone card is not a group, so keeping it highlighted only dims everything else for nothing
        if (selectedPackages.size == 1) selectedPackages.clear()
        state.isMultiSelectMode = false
        search.collapse()
        groupedSelectionPackages?.let { scopePackages ->
            val scopedItems = sourceOrder
                ?.mapNotNull { itemsByPackage[it] }
                ?: orderedItems.filter { it.id in scopePackages }
            val focusIndex = scopedItems.indexOfFirst { it.id in focusTargets }
            scope.launch {
                listState.scrollToItem(focusIndex.coerceAtLeast(0) + leadingItemCount)
                state.isReorderMode = true
            }
        } ?: run {
            state.isReorderMode = true
        }
    }

    val actions = buildList {
        add(
            SelectionAction(
                icon = if (apps.batchQueuePending) MaterialSymbols.Rounded.Playlist_add.autoMirrored else MaterialSymbols.Rounded.Auto_fix_high,
                label = patchSelectedLabel,
                onClick = {
                    appActions.onPatchMultiple(selectedAppItems)
                    state.exitMultiSelect()
                },
                tone = ActionTone.Primary
            )
        )
        // Next to "Patch selected" rather than next to "Hide": both answer what patching these
        // apps does, while "Hide" is about this screen
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Folder_code,
                label = patchSourcesLabel,
                onClick = { state.showPatchSourcesDialog = true },
                tone = ActionTone.Secondary
            )
        )
        if (isCustomCategoryView) {
            add(
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Folder_open,
                    label = moveToCategoryLabel,
                    onClick = { state.showMoveCategoryDialog = true }
                )
            )
        }
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Reorder,
                label = reorderListLabel,
                onClick = enterReorder
            )
        )
        when {
            contextActionIsReinstall -> add(
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Install_mobile,
                    label = reinstallLabel,
                    onClick = {
                        appActions.onReinstallMultiple(selectedReinstallItems)
                        state.exitMultiSelect()
                    }
                )
            )
            contextActionIsUninstall -> add(
                SelectionAction(
                    icon = MaterialSymbols.Rounded.Delete_forever,
                    label = uninstallLabel,
                    onClick = {
                        state.pendingUninstallItems = selectedInstalledItems.toList()
                        state.showBatchUninstallConfirm = true
                    },
                    tone = ActionTone.Destructive
                )
            )
        }
        add(
            SelectionAction(
                icon = MaterialSymbols.Rounded.Visibility_off,
                label = hideLabel,
                onClick = messages.confirming(hiddenMessage) {
                    appActions.onHideMultiple(selectedPackages.keys.toSet())
                    state.exitMultiSelect()
                },
                tone = ActionTone.Destructive
            )
        )
    }

    MultiSelectBar(
        selectedCount = selectedPackages.size,
        totalCount = activeAppScopeItems.size,
        visible = state.isMultiSelectMode || state.isReorderMode,
        onSelectAll = {
            selectedPackages.setAll(activeAppScopeItems.map { it.id })
        },
        onDeselectAll = {
            selectedPackages.clear()
            state.selectedGroupKey = null
        },
        actions = actions,
        onCancel = { state.exitMultiSelect() },
        reorder = MultiSelectReorder(
            isActive = state.isReorderMode,
            onSave = {
                val sourceUid = state.reorderScopeSourceUid
                if (sourceUid != null) {
                    appActions.onSaveSourceOrder(
                        sourceUid,
                        state.scopedSourceOrder ?: reorderItems.map { it.id }
                    )
                } else {
                    appActions.onSaveOrder(state.localOrder)
                }
                state.exitReorder()
            },
            onReset = {
                val sourceUid = state.reorderScopeSourceUid
                if (sourceUid != null) {
                    appActions.onResetSourceOrder(sourceUid)
                } else {
                    appActions.onResetOrder()
                }
                state.exitReorder(homeAppItems.map { it.id })
            },
            onCancel = { state.exitReorder(homeAppItems.map { it.id }) }
        ),
        modifier = modifier
    )

    val activeCategoryTitle = state.activeCategoryId?.let { id ->
        apps.categoryState.categories.firstOrNull { it.id == id }?.name
    }
    val activeSourceTitle = state.activeSourceUid?.let { uid ->
        sourceGroups.firstOrNull { it.sourceUid == uid }?.title
    }
    CategoryActionBar(
        activeCategoryTitle = activeCategoryTitle ?: activeSourceTitle,
        visible = state.isCategoryBarVisible,
        isReorderMode = state.isCategoryReorderMode,
        onRename = {
            val category = apps.categoryState.categories
                .firstOrNull { it.id == state.activeCategoryId }
            if (category != null) {
                state.categoryNameRequest = CategoryNameRequest(category)
            }
            state.activeCategoryId = null
            state.activeSourceUid = null
        },
        onDelete = {
            // Hand off to the confirmation dialog; actual deletion runs only if the user
            // confirms. Close the bar so the dialog isn't shadowed.
            state.pendingDeleteCategoryId = state.activeCategoryId
            state.activeCategoryId = null
            state.activeSourceUid = null
        },
        onEnterReorder = {
            // localSourceGroupOrder is kept in sync by the section's LaunchedEffect, so it
            // already reflects the current source list when reorder begins
            state.activeCategoryId = null
            state.activeSourceUid = null
            search.collapse()
            state.isCategoryReorderMode = true
        },
        onExitReorder = {
            if (isSourceCategoryView) {
                appActions.onSaveSourceGroupOrder(state.localSourceGroupOrder)
            } else {
                appActions.onSaveCategoryOrder(state.localCategoryOrder)
            }
            state.isCategoryReorderMode = false
        },
        onCancel = {
            state.activeCategoryId = null
            state.activeSourceUid = null
        },
        onBack = { state.closeCategoryBar() },
        modifier = modifier,
        showEditActions = state.activeSourceUid == null && !isSourceCategoryView
    )

    ListOptionsPanel(
        visible = state.isListOptionsOpen,
        sections = homeListOptions(apps, appActions),
        onClose = { state.isListOptionsOpen = false },
        modifier = modifier
    )
}

/**
 * How the home list is sorted, filtered and grouped: what its options panel offers, and what marks
 * the sort button once any of it is off its default.
 */
@Composable
internal fun homeListOptions(apps: HomeAppListUi, appActions: HomeAppActions): List<ListOptionSection<*>> = listOf(
    listOptionSection(
        R.string.sort, MaterialSymbols.Rounded.Sort.autoMirrored, apps.sortMode, HomeAppSortMode.RECOMMENDED,
        appActions.onSortModeChange
    ),
    listOptionSection(
        R.string.filter, MaterialSymbols.Rounded.Filter_list, apps.filterMode, HomeAppFilterMode.ALL,
        appActions.onFilterModeChange
    ),
    listOptionSection(
        R.string.group, MaterialSymbols.Rounded.Category, apps.categoryViewMode, HomeAppCategoryViewMode.ALL_APPS,
        appActions.onCategoryViewModeChange
    )
)
