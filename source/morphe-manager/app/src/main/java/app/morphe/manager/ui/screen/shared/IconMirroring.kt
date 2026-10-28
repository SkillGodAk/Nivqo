/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.shared

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath

/**
 * This icon flipped in a right-to-left layout, for one that points along the reading direction,
 * such as an arrow onward or back. Material Symbols leave that to the app, so it is marked here,
 * once per icon.
 */
val ImageVector.autoMirrored: ImageVector
    get() = if (autoMirror) this else mirroredIcons.getOrPut(this) { withAutoMirror() }

private val mirroredIcons = HashMap<ImageVector, ImageVector>()

private fun ImageVector.withAutoMirror(): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = defaultWidth,
    defaultHeight = defaultHeight,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
    tintColor = tintColor,
    tintBlendMode = tintBlendMode,
    autoMirror = true
).apply { root.forEach { addNode(it) } }.build()

private fun ImageVector.Builder.addNode(node: VectorNode) {
    when (node) {
        is VectorPath -> addPath(
            pathData = node.pathData,
            pathFillType = node.pathFillType,
            name = node.name,
            fill = node.fill,
            fillAlpha = node.fillAlpha,
            stroke = node.stroke,
            strokeAlpha = node.strokeAlpha,
            strokeLineWidth = node.strokeLineWidth,
            strokeLineCap = node.strokeLineCap,
            strokeLineJoin = node.strokeLineJoin,
            strokeLineMiter = node.strokeLineMiter,
            trimPathStart = node.trimPathStart,
            trimPathEnd = node.trimPathEnd,
            trimPathOffset = node.trimPathOffset
        )

        is VectorGroup -> {
            addGroup(
                name = node.name,
                rotate = node.rotation,
                pivotX = node.pivotX,
                pivotY = node.pivotY,
                scaleX = node.scaleX,
                scaleY = node.scaleY,
                translationX = node.translationX,
                translationY = node.translationY,
                clipPathData = node.clipPathData
            )
            node.forEach { addNode(it) }
            clearGroup()
        }
    }
}
