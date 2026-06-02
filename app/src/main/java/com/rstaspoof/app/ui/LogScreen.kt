package com.rstaspoof.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import com.rstaspoof.app.ui.terminal.AnsiParser
import com.rstaspoof.app.ui.terminal.TerminalTheme

private val terminalTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 0.sp,
    color = TerminalTheme.foreground,
)

@Composable
fun LogScreen(
    logs: List<String>,
    contentPadding: PaddingValues,
) {
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size, autoScroll) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(TerminalTheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalTheme.header)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "RSTA",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TerminalTheme.cursor,
                ),
            )
            Text(
                text = " — proxy@android",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TerminalTheme.dim,
                ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "auto-scroll",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TerminalTheme.dim,
                ),
            )
            Switch(
                checked = autoScroll,
                onCheckedChange = { autoScroll = it },
                modifier = Modifier.padding(start = 6.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TerminalTheme.cursor,
                    checkedTrackColor = TerminalTheme.green.copy(alpha = 0.35f),
                    uncheckedThumbColor = TerminalTheme.dim,
                    uncheckedTrackColor = TerminalTheme.surface,
                ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(TerminalTheme.surface),
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "$ waiting for proxy output…",
                    style = terminalTextStyle.copy(color = TerminalTheme.dim),
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(
                        items = logs,
                        key = { index, _ -> index },
                    ) { _, line ->
                        TerminalLine(line = line)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalLine(line: String) {
    val horizontalScroll = rememberScrollState()
    val annotated = remember(line) {
        AnsiParser.parse(line, TerminalTheme.defaultSpan)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScroll)
            .padding(vertical = 1.dp),
    ) {
        BasicText(
            text = annotated,
            style = terminalTextStyle,
            softWrap = false,
        )
    }
}
