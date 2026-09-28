/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/**
 * Snapshot-backed set of selection keys shared by the home multi-select bar
 * and the saved-APK dialog. Holds only the keys - a separate explicit
 * "selection mode" flag can live alongside if a caller needs one.
 */
@Stable
class SelectionState<K : Any> internal constructor() {
    private val backing = mutableStateListOf<K>()

    val keys: List<K> get() = backing
    val size: Int get() = backing.size
    val isEmpty: Boolean get() = backing.isEmpty()
    val isNotEmpty: Boolean get() = backing.isNotEmpty()

    fun contains(key: K): Boolean = key in backing

    fun toggle(key: K) {
        if (!backing.remove(key)) backing.add(key)
    }

    fun setAll(keys: Collection<K>) {
        backing.clear()
        backing.addAll(keys.distinct())
    }

    fun clear() {
        backing.clear()
    }

    // Drop keys that no longer satisfy the predicate, e.g. after an external list update
    fun retain(predicate: (K) -> Boolean) {
        backing.removeAll { !predicate(it) }
    }
}

@Composable
fun <K : Any> rememberSelectionState(): SelectionState<K> =
    remember { SelectionState() }
