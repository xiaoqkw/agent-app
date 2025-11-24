package com.example.agent

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

class LabelOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val labels: MutableList<UiLabel> = CopyOnWriteArrayList()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 64, 64)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(14f)
        typeface = Typeface.MONOSPACE
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 122, 255)
        style = Paint.Style.FILL
    }

    fun update(labels: List<UiLabel>) {
        this.labels.clear()
        this.labels.addAll(labels)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (label in labels) {
            val rect = label.rect
            canvas.drawRect(rect, boxPaint)

            val text = label.id.toString()
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            val padding = dp(6f)
            val bubbleRect = RectF(
                rect.left.toFloat(),
                rect.top.toFloat() - textBounds.height() - padding * 2,
                rect.left + textBounds.width() + padding * 2,
                rect.top.toFloat()
            )
            canvas.drawRoundRect(bubbleRect, dp(6f), dp(6f), bubblePaint)
            canvas.drawText(text, bubbleRect.left + padding, bubbleRect.bottom - padding, textPaint)
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
