package com.v2ray.ang.ui.server

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object EditorConstants {
    val FONT_SIZE = 14.sp
    val LINE_HEIGHT = 20.sp
    val LINE_NUMBER_HORIZONTAL_PADDING = 8.dp
    val SCROLLBAR_THICKNESS = 4.dp
    val SCROLLBAR_PADDING = 2.dp
    val SCROLL_PADDING = 60.dp
    val LINE_NUMBER_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = FONT_SIZE, lineHeight = LINE_HEIGHT)
}

fun DrawScope.drawEditorLineNumbers(
    layout: TextLayoutResult,
    textMeasurer: TextMeasurer,
    color: Color
) {
    val lc = layout.lineCount
    for (i in 0 until lc) {
        val lineLabel = (i + 1).toString()
        val measured = textMeasurer.measure(text = lineLabel, style = EditorConstants.LINE_NUMBER_STYLE.copy(color = color, textAlign = TextAlign.End))
        val lineBaseline = layout.getLineBaseline(i)
        val yOffset = lineBaseline - measured.firstBaseline
        val xOffset = size.width - EditorConstants.LINE_NUMBER_HORIZONTAL_PADDING.toPx() - measured.size.width
        drawText(textLayoutResult = measured, topLeft = Offset(x = xOffset.coerceAtLeast(0f), y = yOffset))
    }
}
