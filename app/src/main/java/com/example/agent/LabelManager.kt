package com.example.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/**
 * Builds stable labels for visible, meaningful nodes.
 * Keeps ID stability across refreshes using a stable key map.
 */
class LabelManager(
    private val maxNodes: Int = 400,
    private val minAreaPx: Int = 16 * 16
    ) {

    private val keyToId = LinkedHashMap<String, Int>()
    private var nextId = 1

    @Synchronized
    fun buildLabels(root: AccessibilityNodeInfo?): List<UiLabel> {
        if (root == null) return emptyList()
        val collected = ArrayList<RawNode>(maxNodes)
        traverse(root, 0, collected)

        // Sort smaller & deeper nodes first so overlaps prefer precise targets.
        val sorted = collected
            .distinctBy { it.bounds.flattenToString() to it.className } // basic dedup
            .sortedWith(compareBy<RawNode> { it.area }
                .thenByDescending { it.depth })
            .take(maxNodes)

        val labels = ArrayList<UiLabel>(sorted.size)
        val newKeyToId = LinkedHashMap<String, Int>(sorted.size)

        for (node in sorted) {
            val key = stableKey(node)
            val id = keyToId[key] ?: nextId++
            newKeyToId[key] = id
            labels += UiLabel(
                id = id,
                rect = node.bounds,
                text = node.text,
                className = node.className,
                depth = node.depth
            )
        }

        // Replace map to preserve stability next round.
        keyToId.clear()
        keyToId.putAll(newKeyToId)
        // Reset allocator if no labels to avoid runaway growth.
        if (labels.isEmpty()) nextId = 1
        return labels
    }

    private fun traverse(node: AccessibilityNodeInfo, depth: Int, out: MutableList<RawNode>) {
        try {
            if (!node.isVisibleToUser) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() * bounds.height() < minAreaPx) {
                addChildren(node, depth, out)
                return
            }

            val hasMeaning =
                node.isClickable || node.isEditable || node.isCheckable || node.isFocusable ||
                    !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            if (hasMeaning) {
                out += RawNode(
                    bounds = bounds,
                    text = node.text?.toString()?.ifBlank { node.contentDescription?.toString() },
                    className = node.className?.toString(),
                    depth = depth
                )
            }

            addChildren(node, depth, out)
        } finally {
            // Each node obtained from the framework must be recycled to avoid leaks.
            node.recycle()
        }
    }

    private fun addChildren(node: AccessibilityNodeInfo, depth: Int, out: MutableList<RawNode>) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, depth + 1, out)
        }
    }

    private fun stableKey(node: RawNode): String {
        val textKey = node.text ?: ""
        return "${node.bounds.flattenToString()}:${textKey}:${node.className}:${node.depth}"
    }

    private data class RawNode(
        val bounds: Rect,
        val text: String?,
        val className: String?,
        val depth: Int
    ) {
        val area: Int get() = abs(bounds.width() * bounds.height())
    }
}
