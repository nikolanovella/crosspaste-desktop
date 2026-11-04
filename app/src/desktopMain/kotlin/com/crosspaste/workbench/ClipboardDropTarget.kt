package com.crosspaste.workbench

/** Offscreen items cannot be hit targets. Dropping below the viewport inserts
 * before the next offscreen item, rather than unexpectedly jumping to the end. */
fun clipboardDropTarget(
    order: List<String>,
    visibleCenters: Map<String, Float>,
    y: Float,
): String? {
    val visible = order.filter { it in visibleCenters }
    if (visible.isEmpty()) return null
    return visible.firstOrNull { visibleCenters.getValue(it) > y }
        ?: order.getOrNull(order.indexOf(visible.last()) + 1)
}
