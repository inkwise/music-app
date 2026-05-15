package com.inkwise.music.ui.main.navigationPage.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

class DragReorderState(
    private val listState: LazyListState,
    private val itemCount: Int,
    private val onMove: (Int, Int) -> Unit,
    private val onDragEnd: () -> Unit,
) {
    val _draggedIndex = mutableStateOf<Int?>(null)
    val _dragOffset = mutableStateOf(0f)

    val draggedIndex: State<Int?> = _draggedIndex
    val dragOffset: State<Float> = _dragOffset

    private var draggedIndexValue: Int?
        get() = _draggedIndex.value
        set(value) { _draggedIndex.value = value }

    private var dragOffsetValue: Float
        get() = _dragOffset.value
        set(value) { _dragOffset.value = value }

    private val itemHeightPx: Float
        get() = (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 72).toFloat()

    fun dragModifier(index: Int): Modifier = Modifier
        .pointerInput(index) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    draggedIndexValue = index
                    dragOffsetValue = 0f
                },
                onDragEnd = {
                    val from = draggedIndexValue
                    if (from != null && itemHeightPx > 0f) {
                        val moved = (dragOffsetValue / itemHeightPx).roundToInt()
                        val to = (from + moved).coerceIn(0, itemCount - 1)
                        if (from != to) {
                            onMove(from, to)
                        }
                    }
                    draggedIndexValue = null
                    dragOffsetValue = 0f
                    onDragEnd()
                },
                onDragCancel = {
                    draggedIndexValue = null
                    dragOffsetValue = 0f
                },
                onDrag = { change, offset ->
                    change.consume()
                    dragOffsetValue += offset.y
                }
            )
        }
        .graphicsLayer {
            val from = _draggedIndex.value ?: return@graphicsLayer
            val itemH = itemHeightPx
            if (itemH <= 0f) return@graphicsLayer

            val curOffset = _dragOffset.value

            if (index == from) {
                translationY = curOffset
                scaleX = 1.03f
                scaleY = 1.03f
                return@graphicsLayer
            }

            val target = from.toFloat() + curOffset / itemH

            if (target > from) {
                // 向下拖拽：from 下方的 item 向上让位
                if (index <= from || index > target + 1) return@graphicsLayer
                if (index < target) {
                    translationY = -itemH
                } else {
                    val fraction = target - target.toInt().toFloat()
                    translationY = -itemH * fraction
                }
            } else if (target < from) {
                // 向上拖拽：from 上方的 item 向下让位
                if (index >= from || index < target.toInt()) return@graphicsLayer
                if (index > target) {
                    translationY = itemH
                } else {
                    val fraction = (target.toInt() + 1).toFloat() - target
                    translationY = itemH * fraction
                }
            }
        }
}

@Composable
fun rememberDragReorderState(
    listState: LazyListState,
    itemCount: Int,
    onMove: (Int, Int) -> Unit,
    onDragEnd: () -> Unit = {},
): DragReorderState {
    return remember(listState, itemCount) {
        DragReorderState(listState, itemCount, onMove, onDragEnd)
    }
}
