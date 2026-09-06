/**
 * 列表长按拖拽排序通用组件。
 *
 * [DragReorderState] 为每个列表项提供手势修饰符：长按进入拖拽态，拖动时被拖项
 * 实时跟随手指位移并轻微放大，其余项按"插入位置"做整数或分数位移让位，形成
 * 所见即所得的预览效果；松手时把累计位移换算成跨越的行数得到目标下标，
 * 通过 onMove 回调由调用方一次性完成数据重排（onDragEnd 用于恢复外部状态）。
 */
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

/**
 * 长按拖拽排序的状态持有者。
 *
 * 工作原理：
 * - 列表项通过 [dragModifier] 获得手势与位移动画能力，长按触发后记录 [draggedIndex]，
 *   拖动过程中 [dragOffset] 持续累加纵向位移；
 * - 拖动期间所有位移都只是 graphicsLayer 的视觉变换，数据顺序并不真正改变，
 *   直到松手才计算目标下标并回调 [onMove]，这样中途取消无需回滚数据；
 * - 行高取第一个可见项的高度作为估算值，因此要求列表项等高，否则落点会偏差。
 *
 * @param listState 懒列表状态，用于读取可见项信息估算行高
 * @param itemCount 列表当前条目数，用于把目标下标限制在合法范围
 * @param onMove 拖拽结束后的重排回调，参数为 (from, to)
 * @param onDragEnd 拖拽正常结束（非取消）后的额外回调，如退出多选/长按态
 */
class DragReorderState(
    private val listState: LazyListState,
    private val itemCount: Int,
    private val onMove: (Int, Int) -> Unit,
    private val onDragEnd: () -> Unit,
) {
    // 被拖项的下标与累计拖拽位移，用 mutableStateOf 以便 graphicsLayer 直接读取并触发重绘
    val _draggedIndex = mutableStateOf<Int?>(null)
    val _dragOffset = mutableStateOf(0f)

    // 对外暴露的只读快照，供外部决定是否显示拖拽阴影/禁止滚动等
    val draggedIndex: State<Int?> = _draggedIndex
    val dragOffset: State<Float> = _dragOffset

    // 手势回调中使用的简易读写封装
    private var draggedIndexValue: Int?
        get() = _draggedIndex.value
        set(value) { _draggedIndex.value = value }

    private var dragOffsetValue: Float
        get() = _dragOffset.value
        set(value) { _dragOffset.value = value }

    // 以第一个可见项的高度作为统一行高估算（拿不到布局信息时退回 72px 默认值）
    private val itemHeightPx: Float
        get() = (listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 72).toFloat()

    /**
     * 生成列表项的拖拽手势 + 位移动画修饰符。
     * 列表项只需调用 `Modifier.dragModifier(index)` 即可获得长按拖拽能力。
     */
    fun dragModifier(index: Int): Modifier = Modifier
        .pointerInput(index) {
            // 长按后才进入拖拽态，避免与列表的滚动、点击手势冲突
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    draggedIndexValue = index
                    dragOffsetValue = 0f
                },
                // 松手：把累计位移换算成跨过的行数，得到目标下标并触发一次重排回调
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
                // 取消（如手势被系统打断）：只还原视觉状态，不改动数据顺序
                onDragCancel = {
                    draggedIndexValue = null
                    dragOffsetValue = 0f
                },
                // 拖动中：消费事件（防止父级列表同时滚动）并累加纵向位移
                onDrag = { change, offset ->
                    change.consume()
                    dragOffsetValue += offset.y
                }
            )
        }
        // ── 视觉让位计算：未处于拖拽态时本块不做任何变换 ──
        .graphicsLayer {
            val from = _draggedIndex.value ?: return@graphicsLayer
            val itemH = itemHeightPx
            if (itemH <= 0f) return@graphicsLayer

            val curOffset = _dragOffset.value

            // 被拖项自身：直接跟随手指位移并放大 3%，突出"被拿起"的状态
            if (index == from) {
                translationY = curOffset
                scaleX = 1.03f
                scaleY = 1.03f
                return@graphicsLayer
            }

            // 其余项的"虚拟落点"：原下标 + 位移换算成的浮点行数
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

/**
 * 为 [DragReorderState] 提供 Compose 记忆化包装。
 * 仅以 listState 与 itemCount 作为 key：列表结构变化时重建状态，
 * 而回调（onMove/onDragEnd）变化不触发重建，避免拖拽中途丢失位移进度。
 */
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
