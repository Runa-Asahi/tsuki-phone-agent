package com.runasakura.phoneagent.model

data class UiNode(
    val id: String,
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val viewId: String = "",
    val bounds: String = "",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val children: List<UiNode> = emptyList()
)

data class UiSnapshot(
    val packageName: String,
    val activityHint: String = "",
    val screenWidth: Int,
    val screenHeight: Int,
    val tree: UiNode?,
    val compressedText: String,
    val nodeCount: Int,
    val lowConfidence: Boolean = false
)
