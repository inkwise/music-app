package com.inkwise.music.ui.effect

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.inkwise.music.data.prefs.CoverDisplayMode
import com.inkwise.music.data.prefs.ParticleEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ParticleEffectOverlay(
    effect: ParticleEffect,
    coverDisplayMode: CoverDisplayMode,
    beatIntensity: Float,
    frequencyBands: List<Float>,
    isLightBackground: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (effect == ParticleEffect.NONE) return

    val density = LocalDensity.current
    val sw = with(density) { 2.2f.dp.toPx() }

    val rotFast by rememberInfiniteTransition(label = "rF").animateFloat(
        0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), "rF"
    )
    val rotSlow by rememberInfiniteTransition(label = "rS").animateFloat(
        360f, 0f, infiniteRepeatable(tween(32000, easing = LinearEasing), RepeatMode.Restart), "rS"
    )
    val rotMid by rememberInfiniteTransition(label = "rM").animateFloat(
        0f, 360f, infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Restart), "rM"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = minOf(size.width, size.height) * 0.44f
        val il = isLightBackground
        val band: (Int) -> Float = { i -> frequencyBands.getOrElse(i) { 0f } }

        when (effect) {
            ParticleEffect.STAR_RING -> drawStarRing(this, cx, cy, r, beatIntensity, band, rotFast, rotSlow, sw, il)
            ParticleEffect.WHALE -> drawWhale(this, cx, cy, r, beatIntensity, band, rotSlow, sw, il)
            ParticleEffect.SOUND_WAVE -> drawSoundWave(this, cx, cy, r, beatIntensity, band, size, sw, il)
            ParticleEffect.RHYTHM_GEOMETRY -> drawRhythmGeometry(this, cx, cy, r, beatIntensity, band, rotMid, sw, il)
            else -> {}
        }
    }
}

private fun clr(h: Float, s: Float, l: Float, a: Float, il: Boolean): Color {
    if (il) {
        val adjL = (l * 0.38f).coerceAtMost(0.38f)
        val adjS = s.coerceAtLeast(0.75f)
        return Color.hsl(h % 360f, adjS, adjL, a)
    }
    return Color.hsl(h % 360f, s, l, a)
}

// ═══════════════════════════════════════════════════════════════════
// 星环 — 低频环脉动 + 中频轨道亮点(带辉光) + 高频外层点缀 + 微星
// ═══════════════════════════════════════════════════════════════════

private fun drawStarRing(
    s: DrawScope, cx: Float, cy: Float, r: Float,
    globalBeat: Float, b: (Int) -> Float,
    rotF: Float, rotS: Float, sw: Float, il: Boolean,
) {
    // 3 inner rings — low bands 0,2,4 (sub-bass / bass)
    for (i in 0 until 3) {
        val energy = b(i * 2)
        val ringR = r * (0.86f + i * 0.10f) * (1f + energy * 0.08f)
        val alpha = (0.55f - i * 0.14f) * (0.35f + energy * 0.65f + globalBeat * 0.2f)
        s.drawCircle(
            clr(205f + i * 30f, 0.75f, 0.65f + energy * 0.2f, alpha, il),
            ringR, Offset(cx, cy), style = Stroke(width = sw * (1.6f - i * 0.38f + energy * 0.6f))
        )
    }

    // 8 fast orbital dots with glow — mid bands 6,8,10,12,14,16,18,20
    for (i in 0 until 8) {
        val energy = b(6 + i * 2)
        val a = Math.toRadians((rotF + i * 45f).toDouble())
        val dist = r * (0.93f + energy * 0.09f + globalBeat * 0.02f)
        val x = cx + cos(a).toFloat() * dist
        val y = cy + sin(a).toFloat() * dist
        val dotR = 3f + energy * 9f + globalBeat * 2f
        // glow
        s.drawCircle(clr(190f, 0.85f, 0.72f, (0.1f + energy * 0.35f) * 0.45f, il), dotR * 4f, Offset(x, y))
        // core
        s.drawCircle(clr(190f, 0.9f, 0.75f, 0.3f + energy * 0.55f, il), dotR * 2.8f, Offset(x, y))
        // bright center
        s.drawCircle(clr(190f, 0.3f, 0.96f, 0.45f + energy * 0.45f, il), dotR, Offset(x, y))
    }

    // 6 outer sparkles — high bands 13,15,17,19,21,23
    for (i in 0 until 6) {
        val energy = b(13 + i * 2)
        val a = Math.toRadians((rotS * 0.65f + i * 60f).toDouble())
        val dist = r * (1.08f + energy * 0.1f + globalBeat * 0.04f)
        val dotR = 2f + energy * 6f + globalBeat * 1.5f
        val ax = cx + cos(a).toFloat() * dist
        val ay = cy + sin(a).toFloat() * dist
        s.drawCircle(clr(235f, 0.65f, 0.72f, 0.12f + energy * 0.4f, il), dotR * 3f, Offset(ax, ay))
        s.drawCircle(clr(235f, 0.4f, 0.93f, 0.25f + energy * 0.55f, il), dotR, Offset(ax, ay))
    }

    // 12 tiny stardust — scattered, very responsive to globalBeat
    for (i in 0 until 12) {
        val energy = b((i * 2) % 24).coerceAtLeast(globalBeat * 0.3f)
        val a = Math.toRadians((rotF * 1.4f + i * 30f).toDouble())
        val scatter = r * (1.15f + (i % 3) * 0.06f)
        val sx = cx + cos(a).toFloat() * scatter
        val sy = cy + sin(a).toFloat() * scatter
        s.drawCircle(clr(200f, 0.2f, 0.9f, 0.08f + energy * 0.5f + globalBeat * 0.3f, il),
            1.2f + energy * 2.5f, Offset(sx, sy))
    }
}

// ═══════════════════════════════════════════════════════════════════
// 鲸鱼粒子 — 低频曲线 + 中频编队亮点 + 脉动光晕
// ═══════════════════════════════════════════════════════════════════

private fun drawWhale(
    s: DrawScope, cx: Float, cy: Float, r: Float,
    globalBeat: Float, b: (Int) -> Float,
    rotS: Float, sw: Float, il: Boolean,
) {
    val baseAngle = Math.toRadians(rotS.toDouble())

    // 4 flowing curves — bands 0,3,6,9 (low → mid), increased sampling
    for (j in 0 until 4) {
        val energy = b(j * 3)
        val phase = j * PI.toFloat() / 5f
        val path = Path()
        val steps = 120
        val curveR = r * (1.04f + energy * 0.1f + globalBeat * 0.02f)
        val waveAmp = r * 0.06f * (0.5f + energy * 0.5f + globalBeat * 0.15f)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val a = baseAngle + t * 2 * PI + phase
            val wave = sin(t * 7 * PI.toFloat()) * waveAmp
            val px = cx + cos(a).toFloat() * (curveR + wave)
            val py = cy + sin(a).toFloat() * (curveR + wave)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        s.drawPath(path,
            clr(175f + j * 22f, 0.6f, 0.55f + energy * 0.28f, 0.2f + energy * 0.25f + j * 0.04f, il),
            style = Stroke(width = sw * (0.85f + j * 0.25f + energy * 0.8f)))
    }

    // 16 trail dots with glow — bands 8-23
    for (i in 0 until 16) {
        val energy = b(8 + i % 16)
        val t = i.toFloat() / 16f
        val a = baseAngle + t * 2 * PI
        val wave = sin(t * 7 * PI.toFloat()) * r * 0.055f * (0.5f + energy * 0.5f)
        val dist = r * 1.04f + wave + globalBeat * r * 0.015f
        val x = cx + cos(a).toFloat() * dist
        val y = cy + sin(a).toFloat() * dist
        val dotR = 2f + energy * 5f + globalBeat * 1.5f
        s.drawCircle(clr(190f, 0.65f, 0.72f, 0.1f + energy * 0.35f, il), dotR * 3f, Offset(x, y))
        s.drawCircle(clr(190f, 0.35f, 0.94f, 0.25f + energy * 0.5f, il), dotR * 2.2f, Offset(x, y))
        s.drawCircle(clr(190f, 0.2f, 0.97f, 0.35f + energy * 0.5f, il), dotR, Offset(x, y))
    }
}

// ═══════════════════════════════════════════════════════════════════
// 声波脉动 — 低频驱动环 + 中高频驱动边缘频谱柱(带光晕)
// ═══════════════════════════════════════════════════════════════════

private fun drawSoundWave(
    s: DrawScope, cx: Float, cy: Float, r: Float,
    globalBeat: Float, b: (Int) -> Float,
    size: Size, sw: Float, il: Boolean,
) {
    val w = size.width; val h = size.height

    // 6 concentric pulsing rings — bands 0,3,6,9,12,15 (low→mid-high)
    for (i in 0 until 6) {
        val energy = b((i * 3) % 18)
        val phase = (energy * 4.5f + i * 0.17f) % 1f
        val ringR = r * 0.82f + phase * r * 0.72f
        val alpha = ((1f - phase) * 0.38f * (0.35f + energy * 0.65f + globalBeat * 0.15f)).coerceIn(0f, 0.5f)
        s.drawCircle(
            clr((245f + i * 30f) % 360f, 0.55f, 0.6f + energy * 0.15f, alpha, il),
            ringR, Offset(cx, cy), style = Stroke(width = sw * (0.55f + energy * 1.0f + globalBeat * 0.3f))
        )
    }

    // edge spectrum bars — spread across all 4 sides, 9 bars each
    val barBands = intArrayOf(0, 2, 5, 8, 11, 14, 17, 20, 22)
    val barCount = barBands.size

    fun drawBarRow(xPos: Float, yPos: Float, isHorizontal: Boolean, dir: Float) {
        for (i in 0 until barCount) {
            val energy = b(barBands[i]).coerceAtLeast(globalBeat * 0.15f)
            val barLen = energy * (if (isHorizontal) h * 0.12f else w * 0.07f) + globalBeat * (if (isHorizontal) h * 0.02f else w * 0.01f)
            val alpha = 0.12f + energy * 0.38f + globalBeat * 0.15f
            // glow
            s.drawLine(
                clr(260f, 0.55f, 0.65f, alpha * 0.3f, il),
                Offset(xPos, yPos),
                Offset(xPos + (if (isHorizontal) 0f else barLen * dir), yPos + (if (isHorizontal) barLen * dir else 0f)),
                sw * (1.5f + energy * 1.8f)
            )
            // core
            s.drawLine(
                clr(260f, 0.5f, 0.68f, alpha, il),
                Offset(xPos, yPos),
                Offset(xPos + (if (isHorizontal) 0f else barLen * dir), yPos + (if (isHorizontal) barLen * dir else 0f)),
                sw * (0.8f + energy * 1.2f)
            )
        }
    }

    // Top
    for (i in 0 until barCount) {
        val bx = w * (i + 1f) / (barCount + 1f)
        drawBarRow(bx, 0f, false, 1f)
    }
    // Bottom
    for (i in 0 until barCount) {
        val bx = w * (i + 1f) / (barCount + 1f)
        drawBarRow(bx, h, false, -1f)
    }
    // Left
    for (i in 0 until barCount) {
        val by = h * (i + 1f) / (barCount + 1f)
        drawBarRow(0f, by, true, 1f)
    }
    // Right
    for (i in 0 until barCount) {
        val by = h * (i + 1f) / (barCount + 1f)
        drawBarRow(w, by, true, -1f)
    }
}

// ═══════════════════════════════════════════════════════════════════
// 律动几何 — 低频大形状 + 中频顶点亮点 + 高频点缀 + 内发光
// ═══════════════════════════════════════════════════════════════════

private fun drawRhythmGeometry(
    s: DrawScope, cx: Float, cy: Float, r: Float,
    globalBeat: Float, b: (Int) -> Float,
    rot: Float, sw: Float, il: Boolean,
) {
    // 4 polygons — bands 0,4,8,12 (low→mid)
    val sides = listOf(3, 4, 5, 6)
    for ((idx, n) in sides.withIndex()) {
        val energy = b(idx * 4)
        val polyR = r * (0.58f + idx * 0.14f) * (1f + energy * 0.1f + globalBeat * 0.03f)
        val rotOff = rot * (if (idx % 2 == 0) 1f else -1f) * 0.6f
        val alpha = (0.55f - idx * 0.11f) * (0.35f + energy * 0.65f)
        val path = Path()
        for (i in 0..n) {
            val a = Math.toRadians((rotOff + i * 360.0 / n).toDouble())
            val px = cx + cos(a).toFloat() * polyR
            val py = cy + sin(a).toFloat() * polyR
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        // fill with faint glow
        s.drawPath(
            path,
            clr(30f + idx * 22f, 0.7f, 0.55f + energy * 0.22f, (alpha * 0.12f).coerceIn(0f, 0.1f), il),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
        // stroke
        s.drawPath(path,
            clr(30f + idx * 22f, 0.7f, 0.55f + energy * 0.28f, alpha.coerceIn(0f, 0.6f), il),
            style = Stroke(width = sw * (0.65f + idx * 0.28f + energy * 1.5f + globalBeat * 0.3f)))
    }

    // 8 vertex dots (with glow) — bands 14-21 (mid-high)
    for (i in 0 until 8) {
        val energy = b(14 + i % 8)
        val a = Math.toRadians((rot * 0.5f + i * 45f).toDouble())
        val dist = r * (1.0f + energy * 0.1f + globalBeat * 0.03f)
        val vx = cx + cos(a).toFloat() * dist
        val vy = cy + sin(a).toFloat() * dist
        val dotR = 2.5f + energy * 8f + globalBeat * 2f
        s.drawCircle(clr(40f, 0.85f, 0.72f, 0.1f + energy * 0.4f, il), dotR * 3.5f, Offset(vx, vy))
        s.drawCircle(clr(40f, 0.9f, 0.72f, 0.25f + energy * 0.55f, il), dotR * 2.2f, Offset(vx, vy))
        s.drawCircle(clr(40f, 0.2f, 0.96f, 0.35f + energy * 0.55f, il), dotR, Offset(vx, vy))
    }

    // 8 inner sparkles — bands 16-23 (high range)
    for (i in 0 until 8) {
        val energy = b(16 + i)
        val a = Math.toRadians((rot * 0.55f + 22.5f + i * 45f).toDouble())
        val dist = r * (0.65f + energy * 0.07f + globalBeat * 0.02f)
        val dotR = 1.2f + energy * 4.5f + globalBeat * 1.5f
        s.drawCircle(clr(50f, 0.55f, 0.78f, 0.12f + energy * 0.45f, il),
            dotR * 1.5f, Offset(cx + cos(a).toFloat() * dist, cy + sin(a).toFloat() * dist))
        s.drawCircle(clr(50f, 0.25f, 0.92f, 0.22f + energy * 0.5f, il),
            dotR, Offset(cx + cos(a).toFloat() * dist, cy + sin(a).toFloat() * dist))
    }
}
