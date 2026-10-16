package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.util.LetterJump
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ◀ ▶ in the library list: to the first row of the previous / next letter (see [LetterJump]).
 * The list scrolls there and that row takes the focus; quick presses chain from where the last
 * one is going, and a letter past the loaded pages is loaded first. The letter shows over the
 * list for a moment. The list applies [modifier], gives row [focusIndex] [focus] and reports
 * the focused row through [onRowFocused].
 */
class ListLetterJump internal constructor() {
    val focus = FocusRequester()
    var focusIndex by mutableStateOf<Int?>(null)
        internal set
    internal var letter by mutableStateOf<Char?>(null)
    internal var shown by mutableIntStateOf(0)
    internal var cursor = 0
    internal var job: Job? = null
    internal var far = false
    var modifier: Modifier = Modifier
        internal set
    /** Set while the focus is off the game rows (other sections' search matches under them): ◀ ▶ then move it as usual. */
    var paused = false

    fun onRowFocused(index: Int) {
        cursor = index
    }
}

/**
 * [rowLetters] is the first letter of every loaded row (null when the list is not sorted by
 * name: ◀ ▶ then move the focus as usual). [nextLetterBeyond] finds and loads the next letter
 * when it is past the loaded rows.
 */
@Composable
fun rememberListLetterJump(
    listState: LazyListState,
    rowCount: Int,
    rowLetters: List<Char>?,
    hasMore: Boolean,
    nextLetterBeyond: suspend (Int) -> Int?
): ListLetterJump {
    val jump = remember { ListLetterJump() }
    val scope = rememberCoroutineScope()
    val count by rememberUpdatedState(rowCount)
    val letters by rememberUpdatedState(rowLetters)
    val more by rememberUpdatedState(hasMore)
    val beyond by rememberUpdatedState(nextLetterBeyond)

    fun jumpTo(target: Int) {
        jump.cursor = target
        jump.job?.cancel()
        jump.job = scope.launch {
            // A far jump loads pages first: wait for the row to be there.
            withTimeoutOrNull(5_000) { snapshotFlow { count }.first { it > target } } ?: return@launch
            jump.letter = letters?.getOrNull(target)
            jump.shown++
            jump.focusIndex = target
            try {
                listState.scrollToItem(target)
                withFrameNanos { }
                withFrameNanos { }
                runCatching { jump.focus.requestFocus() }
            } finally {
                if (jump.focusIndex == target) jump.focusIndex = null
            }
        }
    }

    LaunchedEffect(jump.shown) {
        if (jump.letter == null) return@LaunchedEffect
        delay(900)
        jump.letter = null
    }

    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.primary)
    val ground = MaterialTheme.colorScheme.surfaceContainerHigh
    jump.modifier = remember(jump, style, ground) {
        Modifier
            .onPreviewKeyEvent { event ->
                if (jump.paused) return@onPreviewKeyEvent false
                val loaded = letters ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val forward = when (event.key) {
                    Key.DirectionRight -> true
                    Key.DirectionLeft -> false
                    else -> return@onPreviewKeyEvent false
                }
                if (jump.far) return@onPreviewKeyEvent true
                val from = jump.cursor
                val local = if (forward) LetterJump.next(loaded, from) else LetterJump.previous(loaded, from)
                when {
                    local != null -> jumpTo(local)
                    forward && more -> {
                        jump.far = true
                        scope.launch {
                            try {
                                beyond(from)?.let { jumpTo(it) }
                            } finally {
                                jump.far = false
                            }
                        }
                    }
                    // ◀ on the first letter moves on as before (to the filter panel).
                    !forward -> return@onPreviewKeyEvent false
                }
                true
            }
            // The letter of the last jump, top right; drawn only (no layout, no recomposition).
            .drawWithContent {
                drawContent()
                val letter = jump.letter ?: return@drawWithContent
                val text = measurer.measure(letter.toString(), style)
                val box = 52.dp.toPx()
                val left = size.width - box - 8.dp.toPx()
                val top = 8.dp.toPx()
                drawRoundRect(ground, Offset(left, top), Size(box, box), CornerRadius(10.dp.toPx()))
                drawText(text, topLeft = Offset(left + (box - text.size.width) / 2, top + (box - text.size.height) / 2))
            }
    }
    return jump
}
