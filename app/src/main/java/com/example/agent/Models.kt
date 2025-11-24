package com.example.agent

import android.graphics.Rect

data class UiLabel(
    val id: Int,
    val rect: Rect,
    val text: String?,
    val className: String?,
    val depth: Int
)
