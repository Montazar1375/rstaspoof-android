package com.rstaspoof.app.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
object AnsiParser {
    private val sequence = Regex("""\u001B\[([0-9;]*)m""")

    fun parse(line: String, base: SpanStyle = TerminalTheme.defaultSpan): AnnotatedString {
        if (!line.contains('\u001B')) {
            return AnnotatedString(line, base)
        }
        val builder = AnnotatedString.Builder()
        var style = base
        var pos = 0
        for (match in sequence.findAll(line)) {
            if (match.range.first > pos) {
                builder.pushStyle(style)
                builder.append(line.substring(pos, match.range.first))
                builder.pop()
            }
            pos = match.range.last + 1
            style = applyCodes(base, style, match.groupValues[1])
        }
        if (pos < line.length) {
            builder.pushStyle(style)
            builder.append(line.substring(pos))
            builder.pop()
        }
        return builder.toAnnotatedString()
    }

    private fun applyCodes(base: SpanStyle, current: SpanStyle, codeGroup: String): SpanStyle {
        if (codeGroup.isEmpty()) return base
        val codes = codeGroup.split(';').mapNotNull { it.toIntOrNull() }
        var style = current
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> style = base
                1 -> style = style.copy(fontWeight = FontWeight.Bold)
                2 -> style = style.copy(color = style.color?.copy(alpha = 0.65f) ?: base.color)
                22 -> style = style.copy(fontWeight = FontWeight.Normal)
                39 -> style = style.copy(color = base.color)
                in 30..37 -> style = style.copy(color = ansiColor(code - 30, bright = false))
                in 90..97 -> style = style.copy(color = ansiColor(code - 90, bright = true))
                38 -> i += if (i + 1 < codes.size && codes[i + 1] == 5) 2 else 1
                else -> Unit
            }
            i++
        }
        return style
    }

    private fun ansiColor(index: Int, bright: Boolean): Color = when (index) {
        0 -> if (bright) TerminalTheme.brightBlack else TerminalTheme.black
        1 -> if (bright) TerminalTheme.brightRed else TerminalTheme.red
        2 -> if (bright) TerminalTheme.brightGreen else TerminalTheme.green
        3 -> if (bright) TerminalTheme.brightYellow else TerminalTheme.yellow
        4 -> if (bright) TerminalTheme.brightBlue else TerminalTheme.blue
        5 -> if (bright) TerminalTheme.brightMagenta else TerminalTheme.magenta
        6 -> if (bright) TerminalTheme.brightCyan else TerminalTheme.cyan
        7 -> if (bright) TerminalTheme.brightWhite else TerminalTheme.white
        else -> TerminalTheme.foreground
    }
}

object TerminalTheme {
    val background = Color(0xFF0C0C0C)
    val surface = Color(0xFF161616)
    val header = Color(0xFF252526)
    val foreground = Color(0xFFCCCCCC)
    val dim = Color(0xFF808080)
    val cursor = Color(0xFF4AF626)

    val black = Color(0xFF3F3F3F)
    val red = Color(0xFFE06C75)
    val green = Color(0xFF98C379)
    val yellow = Color(0xFFE5C07B)
    val blue = Color(0xFF61AFEF)
    val magenta = Color(0xFFC678DD)
    val cyan = Color(0xFF56B6C2)
    val white = Color(0xFFD4D4D4)

    val brightBlack = Color(0xFF5A5A5A)
    val brightRed = Color(0xFFFF6B6B)
    val brightGreen = Color(0xFFB5E890)
    val brightYellow = Color(0xFFFFD93D)
    val brightBlue = Color(0xFF79C0FF)
    val brightMagenta = Color(0xFFD8A6FF)
    val brightCyan = Color(0xFF6CEFFF)
    val brightWhite = Color(0xFFFFFFFF)

    val defaultSpan = SpanStyle(
        color = foreground,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    )
}
