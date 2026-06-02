package com.sniray.app.v2ray.util

import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.annotation.ColorInt
import com.sniray.app.R

object AnsiParser {
    private val sequence = Regex("""\u001B\[([0-9;]*)m""")

    fun parse(line: String, @ColorInt baseColor: Int = TerminalColors.foreground): SpannableString {
        if (!line.contains('\u001B')) {
            return SpannableString(line).apply {
                setSpan(ForegroundColorSpan(baseColor), 0, length, 0)
            }
        }
        val builder = SpannableStringBuilder()
        var color = baseColor
        var bold = false
        var pos = 0
        for (match in sequence.findAll(line)) {
            if (match.range.first > pos) {
                appendStyled(builder, line.substring(pos, match.range.first), color, bold)
            }
            pos = match.range.last + 1
            val codes = applyCodes(baseColor, color, bold, match.groupValues[1])
            color = codes.first
            bold = codes.second
        }
        if (pos < line.length) {
            appendStyled(builder, line.substring(pos), color, bold)
        }
        return SpannableString(builder)
    }

    private fun appendStyled(
        builder: SpannableStringBuilder,
        text: String,
        @ColorInt color: Int,
        bold: Boolean,
    ) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        builder.setSpan(ForegroundColorSpan(color), start, end, 0)
        if (bold) {
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, 0)
        }
    }

    private fun applyCodes(
        @ColorInt baseColor: Int,
        @ColorInt currentColor: Int,
        currentBold: Boolean,
        codeGroup: String,
    ): Pair<Int, Boolean> {
        if (codeGroup.isEmpty()) return baseColor to false
        val codes = codeGroup.split(';').mapNotNull { it.toIntOrNull() }
        var color = currentColor
        var bold = currentBold
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> {
                    color = baseColor
                    bold = false
                }
                1 -> bold = true
                2 -> color = dimColor(color)
                22 -> bold = false
                39 -> color = baseColor
                in 30..37 -> color = ansiColor(code - 30, bright = false)
                in 90..97 -> color = ansiColor(code - 90, bright = true)
                38 -> i += if (i + 1 < codes.size && codes[i + 1] == 5) 2 else 1
                else -> Unit
            }
            i++
        }
        return color to bold
    }

    @ColorInt
    private fun dimColor(@ColorInt color: Int): Int {
        val alpha = (android.graphics.Color.alpha(color) * 0.65f).toInt()
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }

    @ColorInt
    private fun ansiColor(index: Int, bright: Boolean): Int = when (index) {
        0 -> if (bright) TerminalColors.brightBlack else TerminalColors.black
        1 -> if (bright) TerminalColors.brightRed else TerminalColors.red
        2 -> if (bright) TerminalColors.brightGreen else TerminalColors.green
        3 -> if (bright) TerminalColors.brightYellow else TerminalColors.yellow
        4 -> if (bright) TerminalColors.brightBlue else TerminalColors.blue
        5 -> if (bright) TerminalColors.brightMagenta else TerminalColors.magenta
        6 -> if (bright) TerminalColors.brightCyan else TerminalColors.cyan
        7 -> if (bright) TerminalColors.brightWhite else TerminalColors.white
        else -> TerminalColors.foreground
    }
}

object TerminalColors {
    @ColorInt var foreground: Int = 0
    @ColorInt var black: Int = 0
    @ColorInt var red: Int = 0
    @ColorInt var green: Int = 0
    @ColorInt var yellow: Int = 0
    @ColorInt var blue: Int = 0
    @ColorInt var magenta: Int = 0
    @ColorInt var cyan: Int = 0
    @ColorInt var white: Int = 0
    @ColorInt var brightBlack: Int = 0
    @ColorInt var brightRed: Int = 0
    @ColorInt var brightGreen: Int = 0
    @ColorInt var brightYellow: Int = 0
    @ColorInt var brightBlue: Int = 0
    @ColorInt var brightMagenta: Int = 0
    @ColorInt var brightCyan: Int = 0
    @ColorInt var brightWhite: Int = 0

    fun init(context: android.content.Context) {
        val res = context.resources
        foreground = res.getColor(R.color.rsta_terminal_foreground, context.theme)
        black = res.getColor(R.color.rsta_terminal_black, context.theme)
        red = res.getColor(R.color.rsta_terminal_red, context.theme)
        green = res.getColor(R.color.rsta_terminal_green, context.theme)
        yellow = res.getColor(R.color.rsta_terminal_yellow, context.theme)
        blue = res.getColor(R.color.rsta_terminal_blue, context.theme)
        magenta = res.getColor(R.color.rsta_terminal_magenta, context.theme)
        cyan = res.getColor(R.color.rsta_terminal_cyan, context.theme)
        white = res.getColor(R.color.rsta_terminal_white, context.theme)
        brightBlack = res.getColor(R.color.rsta_terminal_bright_black, context.theme)
        brightRed = res.getColor(R.color.rsta_terminal_bright_red, context.theme)
        brightGreen = res.getColor(R.color.rsta_terminal_bright_green, context.theme)
        brightYellow = res.getColor(R.color.rsta_terminal_bright_yellow, context.theme)
        brightBlue = res.getColor(R.color.rsta_terminal_bright_blue, context.theme)
        brightMagenta = res.getColor(R.color.rsta_terminal_bright_magenta, context.theme)
        brightCyan = res.getColor(R.color.rsta_terminal_bright_cyan, context.theme)
        brightWhite = res.getColor(R.color.rsta_terminal_bright_white, context.theme)
    }
}
