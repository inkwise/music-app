package com.inkwise.music.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import kotlin.math.abs

// =====================================================================
// 封面像素明亮化处理（对标 Salt Player ou0.smali，仅在 flowingLightMode>=1 时使用）
// =====================================================================

fun processCoverForBackground(bitmap: Bitmap, targetSize: Int = 280): Bitmap {
    val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, false)
    val mutable = if (scaled.isMutable) scaled
        else scaled.copy(scaled.config!!, true)

    for (y in 0 until mutable.height) {
        for (x in 0 until mutable.width) {
            val pixel = mutable.getPixel(x, y)
            val alpha = AndroidColor.alpha(pixel)
            var r = AndroidColor.red(pixel)
            var g = AndroidColor.green(pixel)
            var b = AndroidColor.blue(pixel)

            if (r < 128 || g < 128 || b < 128) {
                r = (r + 255) / 2
                g = (g + 255) / 2
                b = (b + 255) / 2

                if (abs(abs(r - g) - abs(g - b)) < 10) {
                    r = 255; g = 255; b = 255
                }
            }
            mutable.setPixel(x, y, AndroidColor.argb(alpha, r, g, b))
        }
    }
    return mutable
}

// =====================================================================
// 封面背景预处理（对标 Salt Player rg View：2.5x 饱和度增强）
// =====================================================================

/** Salt Player rg View 默认饱和度增强系数 */
private const val SATURATION_BOOST = 2.5f

/**
 * 对标 Salt Player rg View 的离屏渲染管线：
 * 1. 缩放到 280x280
 * 2. 应用 ColorMatrix setSaturation(2.5f) 增强色彩
 * 3. 逐像素明亮化（暗色→雾白），模拟亮色模式下的梦幻效果
 */
fun prepareCoverForBackground(bitmap: Bitmap, targetSize: Int = 280): Bitmap {
    val scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, false)

    // Step 1: 饱和度增强（对标 rg View onDraw ColorMatrix）
    val cm = ColorMatrix()
    cm.setSaturation(SATURATION_BOOST)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.colorFilter = ColorMatrixColorFilter(cm)

    val saturated = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(saturated)
    canvas.drawBitmap(scaled, 0f, 0f, paint)
    if (scaled !== saturated) scaled.recycle()

    // Step 2: 像素明亮化（对标 ou0.smali，亮色模式下生效）
    for (y in 0 until saturated.height) {
        for (x in 0 until saturated.width) {
            val pixel = saturated.getPixel(x, y)
            val alpha = AndroidColor.alpha(pixel)
            var r = AndroidColor.red(pixel)
            var g = AndroidColor.green(pixel)
            var b = AndroidColor.blue(pixel)

            if (r < 128 || g < 128 || b < 128) {
                r = (r + 255) / 2
                g = (g + 255) / 2
                b = (b + 255) / 2

                if (abs(abs(r - g) - abs(g - b)) < 10) {
                    r = 255; g = 255; b = 255
                }
            }
            saturated.setPixel(x, y, AndroidColor.argb(alpha, r, g, b))
        }
    }
    return saturated
}

// =====================================================================
// Palette 主题色提取（对标 Salt Player nz0 + ܪ 选择逻辑）
// =====================================================================

private val FALLBACK_DARK = 0xFF282828.toInt()

/** Hue-Saturation-Lightness for swatch selection */
private fun getHsl(color: Int): FloatArray {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color, hsl)
    return hsl
}

/**
 * 对标 nz0.Ԫ() — population 加权 swatch 选择器。
 * 在候选 swatch 中按 population 比例 + 饱和度加权选择最佳色。
 */
private fun selectSwatch(
    p0: Palette.Swatch?,
    p1: Palette.Swatch?,
    p2: Palette.Swatch?,
    p3: Palette.Swatch?,
    dominant: Palette.Swatch?,
    fallback: Int
): Int {
    fun valid(s: Palette.Swatch?) = s != null && s.population > 45

    // Step 1: p0 vs p1 — population 比较
    var selected: Palette.Swatch? = null
    if (valid(p0) && valid(p1)) {
        selected = if (p0!!.population.toFloat() / p1!!.population < 1.0f) p1 else p0
    } else if (valid(p0)) {
        selected = p0
    } else if (valid(p1)) {
        selected = p1
    }

    // Step 2: p2 vs p3 — population*saturation 加权
    if (selected == null) {
        if (valid(p2) && valid(p3)) {
            val hsl2 = getHsl(p2!!.rgb)
            val hsl3 = getHsl(p3!!.rgb)
            val score = (p2.population.toFloat() / p3.population) * hsl2[1]
            selected = if (score > hsl3[1]) p3 else p2
        } else if (valid(p2)) {
            selected = p2
        } else if (valid(p3)) {
            selected = p3
        }
    }

    // Step 3: 与 dominant 比较
    if (selected != null) {
        if (selected == dominant) return selected.rgb
        if (dominant != null && selected.population.toFloat() / dominant.population < 0.01f) {
            val hslDom = getHsl(dominant.rgb)
            if (hslDom[1] > 0.19f) return dominant.rgb
        }
        return selected.rgb
    }

    // Step 4: 回退
    return dominant?.rgb ?: fallback
}

/**
 * 从封面 Bitmap 提取主题色。对标 Salt Player nz0.constructor Phase 3:
 * - 亮色 dominant (L > 0.5): 从 light variants 中选 → 应用 LAB L-20 偏移（更深）
 * - 暗色 dominant: 从 vibrant/dark variants 中选 → 与 #282828 混合
 */
fun extractThemeColor(bitmap: Bitmap): Int {
    val scaled = Bitmap.createScaledBitmap(bitmap, 120, 120, false)
    val palette = Palette.from(scaled).maximumColorCount(16).generate()
    scaled.recycle()

    val dominant = palette.dominantSwatch
    val vibrant = palette.vibrantSwatch
    val muted = palette.mutedSwatch
    val darkVibrant = palette.darkVibrantSwatch
    val darkMuted = palette.darkMutedSwatch
    val lightVibrant = palette.lightVibrantSwatch
    val lightMuted = palette.lightMutedSwatch

    if (dominant == null && vibrant == null && muted == null) return FALLBACK_DARK

    val dominantColor = dominant?.rgb ?: vibrant?.rgb ?: muted?.rgb ?: return FALLBACK_DARK
    val dominantIsLight = getHsl(dominantColor)[2] > 0.5f

    // 统一后处理：混少量黑色 + 亮度平衡
    val rawColor = if (dominantIsLight) {
        selectSwatch(lightVibrant, muted, lightMuted, darkMuted, dominant, 0xFF000000.toInt())
    } else {
        selectSwatch(vibrant, muted, darkVibrant, darkMuted, dominant, FALLBACK_DARK)
    }
    return mixBlackAndBalance(rawColor)
}

/**
 * 混入 20% 黑色 → 保持主题色调的同时提深。
 * 然后亮度钳位到 0.38~0.50：既能在浅色背景上看清，又不失主题色。
 */
private fun mixBlackAndBalance(color: Int): Int {
    // 60% 黑色混合
    val r = (AndroidColor.red(color) * 0.40f).toInt()
    val g = (AndroidColor.green(color) * 0.40f).toInt()
    val b = (AndroidColor.blue(color) * 0.40f).toInt()
    val mixed = AndroidColor.rgb(r, g, b)

    // 亮度平衡：太亮看不清，太暗丢失主题色
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(mixed, hsl)
    hsl[2] = hsl[2].coerceIn(0.34f, 0.48f)
    // 保持饱和度不低于 20%，确保主题色可辨识
    hsl[1] = hsl[1].coerceAtLeast(0.20f)
    return ColorUtils.HSLToColor(hsl)
}

// =====================================================================
// 自定义直方图取色（备用：更快的方案）
// =====================================================================

/**
 * 从封面 Bitmap 提取主导颜色（直方图方案）。
 */
fun extractDominantColor(bitmap: Bitmap): Int {
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 100, 100, false)

    val colorCount = mutableMapOf<Int, Int>()
    for (x in 0 until scaledBitmap.width) {
        for (y in 0 until scaledBitmap.height) {
            val pixel = scaledBitmap.getPixel(x, y)
            val quantizedColor = quantizeColor(pixel)
            colorCount[quantizedColor] = (colorCount[quantizedColor] ?: 0) + 1
        }
    }

    val sortedColors = colorCount.entries
        .sortedByDescending { it.value }
        .map { it.key }

    val dominantColor = sortedColors.firstOrNull { color ->
        val hsl = rgbToHsl(color)
        hsl[2] in 0.15f..0.85f
                && hsl[1] > 0.1f
    } ?: sortedColors.first()

    scaledBitmap.recycle()
    return dominantColor
}

fun quantizeColor(color: Int): Int {
    val r = ((color shr 16) and 0xFF) and 0xF0
    val g = ((color shr 8) and 0xFF) and 0xF0
    val b = (color and 0xFF) and 0xF0
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun rgbToHsl(color: Int): FloatArray {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f

    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f

    val s = if (max == min) 0f else {
        if (l > 0.5f) (max - min) / (2f - max - min)
        else (max - min) / (max + min)
    }

    var h = if (max == min) 0f else {
        when (max) {
            r -> ((g - b) / (max - min)) + (if (g < b) 6f else 0f)
            g -> ((b - r) / (max - min)) + 2f
            else -> ((r - g) / (max - min)) + 4f
        }
    }
    h /= 6f
    return floatArrayOf(h, s, l)
}

// =====================================================================
// 颜色变体工具
// =====================================================================

fun darkenColor(color: Color, factor: Float): Color {
    val r = (color.red * (1f - factor))
    val g = (color.green * (1f - factor))
    val b = (color.blue * (1f - factor))
    return Color(r, g, b, color.alpha)
}

fun lightenColor(color: Color, factor: Float): Color {
    val r = color.red + ((1f - color.red) * factor)
    val g = color.green + ((1f - color.green) * factor)
    val b = color.blue + ((1f - color.blue) * factor)
    return Color(r, g, b, color.alpha)
}

fun isColorDark(color: Color): Boolean {
    val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue)
    return luminance < 0.5f
}

fun Color.toSoftBackground(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(this.toArgb(), hsl)

    hsl[1] = (hsl[1] * 0.2f).coerceAtMost(0.12f)
    hsl[2] = 0.97f

    return Color(ColorUtils.HSLToColor(hsl))
}

fun harmonizeToPlayerBackground(colorInt: Int): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(colorInt, hsl)

    val saturation = hsl[1]
    val lightness = hsl[2]

    if (lightness > 0.6f) {
        hsl[1] = (saturation * 0.6f).coerceAtMost(0.6f)
        hsl[2] = lightness * 0.90f
    } else {
        hsl[1] = (saturation * 0.5f).coerceAtMost(0.5f)
        hsl[2] = 0.85f
    }

    return Color(ColorUtils.HSLToColor(hsl))
}
