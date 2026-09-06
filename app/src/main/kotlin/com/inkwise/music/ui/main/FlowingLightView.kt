package com.inkwise.music.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 椒盐音乐"流光"播放页背景 —— 按 rg.smali 真实渲染管线实现（参数逐项对齐）。
 *
 * 管线（椒盐 v11 反编译还原）：
 *  1. 缓冲：bw=round(w*1.3/D)、bh=round(h*1.3/D)，D=dpi≥420?20:32（减弱流光 48/72）
 *  2. 封面缩放：S=round(max(bw,bh)*1.3)，k=S/封面.高，三层 Paint(0x7)+饱和度 2.5 的
 *     ColorMatrix 不透明绘制，绕封面中心自转（L1/L2），L3 再绕缓冲中心公转：
 *     L1=T(cx,cy)·R(a1,S/2)·Sc  a1: 0→+360°/100s
 *     L2=L1+T(-0.95bw,-0.7bh)   a2: 0→-360°/70s
 *     L3=L1+T(-0.5bw,+0.7bh) 再 R(a3,bw/2,bh/2)  a3: 0→-360°/40s
 *  3. 附加流光色（主题半透明白/黑）在模糊前整幅叠染
 *  4. 整幅高斯模糊（RenderScript ScriptIntrinsicBlur 半径 25，不可用时退化为盒式模糊）
 *  5. 上屏：BitmapShader 放大 D 倍铺满 1.3×屏幕并居中（等价 drawBitmap 到 1.3×矩形），
 *     换歌时新旧壁纸 500ms 缓动（PathInterpolator(0,0,0.3,1)）交叉淡出
 * 椒盐的 5×5 网格扭曲在 v11 构建中顶点全 0（失效），不复刻。
 */
class FlowingLightView @JvmOverloads constructor(
    context: Context,
) : View(context) {

    // ── 椒盐 rg.smali 原参数 ──
    private val dpiHi = resources.configuration.densityDpi >= 420
    /** 缓冲分辨率除数（减弱流光时更低） */
    private val downScale = if (dpiHi) 20f else 32f
    private val reduceDownScale = if (dpiHi) 48f else 72f
    /** 封面饱和度（减弱流光时 3.5） */
    private val saturation = 2.5f
    private val reduceSaturation = 3.5f
    /** 封面/上屏放大边距 */
    private val margin = 1.3f
    /** 高斯模糊半径（椒盐 RS 常数） */
    private val blurRadius = 25f
    /** 换歌交叉淡出（椒盐 AlphaAnimation 500ms + PathInterpolator(0,0,0.3,1)） */
    private val crossfadeMs = 500L
    private val fadeInterpolator = PathInterpolator(0f, 0f, 0.3f, 1f)

    private val rotate1 = anim(0f, 360f, 100_000L)
    private val rotate2 = anim(0f, -360f, 70_000L)
    private val rotate3 = anim(0f, -360f, 40_000L)

    private val angle1 get() = (rotate1.animatedValue as? Float) ?: 0f
    private val angle2 get() = (rotate2.animatedValue as? Float) ?: 0f
    private val angle3 get() = (rotate3.animatedValue as? Float) ?: 0f

    // ── 状态 ──
    private var artwork: Bitmap? = null
    /** 实际参与绘制的封面（浅色流光=雾白化处理版，深色=原图），换歌时重算一次 */
    private var drawSource: Bitmap? = null
    /** 浅色流光雾白化开关（对标椒盐 ou0：暗封面洗白，黑封面背景接近纯白） */
    private var brighten = false
    private var wallpaper: Bitmap? = null        // 当前模糊壁纸
    private var prevWallpaper: Bitmap? = null    // 上一张（换歌交叉淡出）
    private var fadeStart = 0L

    private var dynamic = false                  // "动态流光"：开=逐帧重建+自续
    private var renderActive = true              // 播放器展开态驱动
    private var reduced = false                  // "减弱流光效果"
    private var additionalColors = IntArray(0)
    private var topCornerRadius = 0f
    private var fps = 30f

    // 椒盐三层共用 Paint(0x7)：ANTI_ALIAS | FILTER_BITMAP | DITHER + 饱和度 ColorMatrix
    private var paintLayer = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    ).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })
    }
    private val paintWall = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    private val paintFill = Paint().apply { style = Paint.Style.FILL }
    private val clipPath = Path()
    private val clipRect = RectF()
    private val clipRadii = FloatArray(8)

    private fun anim(from: Float, to: Float, duration: Long) =
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }

    // ── 外部 API ──

    /** 设置封面；旧壁纸保留 500ms 交叉淡出（椒盐换歌柔和过渡） */
    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        if (bitmap.sameAs(artwork)) return
        prevWallpaper = wallpaper
        wallpaper = null
        artwork = bitmap
        drawSource = if (brighten) fogWhiten(bitmap) else bitmap
        fadeStart = System.currentTimeMillis()
        if (renderActive && dynamic && !reduced) startAnimators()
        invalidate()
    }

    /**
     * 雾白化开关（浅色流光开、深色流光关）。切换时对当前封面重算并重建壁纸。
     */
    fun setCoverBrighten(on: Boolean) {
        if (brighten == on) return
        brighten = on
        val art = artwork
        drawSource = if (on && art != null && !art.isRecycled) fogWhiten(art) else art
        wallpaper = null
        invalidate()
    }

    /**
     * 椒盐 ou0 像素明亮化（雾白化）：任何通道 <128 的暗像素与白平均 (c+255)/2；
     * 平均后若近似中性灰（abs(|r-g|-|g-b|)<10）直接置纯白。
     * 黑色/深灰封面经此整张洗白 —— 对应椒盐"黑封面背景反而更白"的现象。
     */
    private fun fogWhiten(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            var r = (p ushr 16) and 0xFF
            var g = (p ushr 8) and 0xFF
            var b = p and 0xFF
            if (r < 128 || g < 128 || b < 128) {
                r = (r + 255) / 2
                g = (g + 255) / 2
                b = (b + 255) / 2
                if (kotlin.math.abs(kotlin.math.abs(r - g) - kotlin.math.abs(g - b)) < 10) {
                    r = 255; g = 255; b = 255
                }
            }
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** "动态流光"开关：开=旋转动画+逐帧重建；关=静态帧（换歌淡出仍会短暂续帧） */
    fun setDynamic(on: Boolean) {
        dynamic = on
        if (on && renderActive && !reduced) startAnimators() else stopAnimators()
        invalidate()
    }

    /** 播放器展开态驱动：收起时停全部动画与帧循环 */
    fun setRenderActive(active: Boolean) {
        if (renderActive == active) return
        renderActive = active
        if (active && dynamic && !reduced) startAnimators() else stopAnimators()
        invalidate()
    }

    /** 椒盐"减弱流光效果"：更低内部分辨率 + 3.5 饱和 + 停动画 */
    fun setReducedEffects(on: Boolean) {
        reduced = on
        paintLayer = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
        ).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(if (on) reduceSaturation else saturation) }
            )
        }
        wallpaper = null
        if (on) stopAnimators() else if (renderActive && dynamic) startAnimators()
        invalidate()
    }

    /** 附加流光色（浅=半透明白 / 深=半透明黑），模糊前整幅叠染 */
    fun setFlowingLightAdditionalColors(colors: IntArray) {
        additionalColors = colors
        wallpaper = null
        invalidate()
    }

    /** 顶部圆角半径(px)；椒盐限制不超过 8dp */
    fun setTopCornerRadius(radius: Float) {
        val maxPx = 8f * resources.displayMetrics.density
        topCornerRadius = min(radius, maxPx)
        rebuildPath()
        invalidate()
    }

    fun setFps(value: Float) {
        fps = value.coerceIn(10f, 60f)
    }

    // ── 圆角路径 ──

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath()
    }

    private fun rebuildPath() {
        clipPath.reset()
        val r = topCornerRadius
        if (r <= 0f) return
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipRadii[0] = r; clipRadii[1] = r      // 上两角圆角
        clipRadii[2] = r; clipRadii[3] = r
        clipRadii[4] = 0f; clipRadii[5] = 0f    // 下两角直角
        clipRadii[6] = 0f; clipRadii[7] = 0f
        clipPath.addRoundRect(clipRect, clipRadii, Path.Direction.CW)
    }

    private fun startAnimators() {
        listOf(rotate1, rotate2, rotate3).forEach { if (!it.isStarted) it.start() }
    }

    private fun stopAnimators() {
        listOf(rotate1, rotate2, rotate3).forEach { it.cancel() }
    }

    // ── 绘制 ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val art = drawSource
        if (art == null || art.isRecycled) {
            // 无封面占位：椒盐浅色流光下占位图同样走雾白化（背景近白），深色用黑
            canvas.drawColor(if (brighten) Color.WHITE else Color.BLACK)
            return
        }

        // 动态开启=每帧重建（流光流动）；静态=仅壁纸缺失时重建一次
        if (wallpaper == null || (renderActive && dynamic && !reduced)) {
            rebuildWallpaper(art)
        }

        // 换歌交叉淡出：t 为新壁纸淡入进度（缓动同椒盐 PathInterpolator）
        val raw = if (prevWallpaper != null) {
            (System.currentTimeMillis() - fadeStart).toFloat() / crossfadeMs
        } else 1f
        val t = fadeInterpolator.getInterpolation(raw.coerceIn(0f, 1f))

        canvas.save()
        if (topCornerRadius > 0f) canvas.clipPath(clipPath)

        // 上屏 = 模糊壁纸放大 1.3× 并居中（椒盐 localMatrix：scale(D)+居中只露中央 77%）
        val dst = RectF(
            -(margin - 1f) * w, -(margin - 1f) * h,
            w * margin, h * margin,
        )

        val old = prevWallpaper
        if (old != null && !old.isRecycled && t < 1f) {
            paintWall.alpha = 255 // 旧壁纸垫底全不透明
            canvas.drawBitmap(old, null, dst, paintWall)
        }
        val wall = wallpaper
        if (wall != null && !wall.isRecycled) {
            paintWall.alpha = (t * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(wall, null, dst, paintWall)
        }
        canvas.restore()

        // 淡出结束清引用
        if (raw >= 1f && prevWallpaper != null) prevWallpaper = null

        // 自续帧：动态=按 fps；静态但淡出未完=短间隔续帧直至淡出完成
        if (isAttachedToWindow) {
            if (renderActive && dynamic && !reduced) {
                postInvalidateDelayed((1000f / fps).toLong())
            } else if (raw < 1f) {
                postInvalidateDelayed(16)
            }
        }
    }

    /**
     * 椒盐 onDraw 重建分支：小缓冲三层旋转封面（马赛克）+ 附加色叠染 → 整幅高斯模糊。
     * 缓冲约 屏宽/20，逐帧模糊仍廉价。
     */
    private fun rebuildWallpaper(src: Bitmap) {
        val wPx = width.toFloat()
        val hPx = height.toFloat()
        if (wPx <= 0f || hPx <= 0f) return

        val d = if (reduced) reduceDownScale else downScale
        val wB = (wPx * margin / d).roundToInt().coerceAtLeast(1)
        val hB = (hPx * margin / d).roundToInt().coerceAtLeast(1)

        // 椒盐：S=round(max(bw,bh)*1.3)，k=S/封面高；cx=(bw-S)/2, cy=(bh-S)/2
        val s = (max(wB, hB) * margin).roundToInt()
        val k = s.toFloat() / max(1, src.height)
        val half = s / 2f
        val cx = (wB - s) / 2f
        val cy = (hB - s) / 2f

        val buf = Bitmap.createBitmap(wB, hB, Bitmap.Config.ARGB_8888)
        val c = Canvas(buf)

        /**
         * 椒盐逐层矩阵：setScale → postRotate(绕封面中心自转) → postTranslate(居中+错位)；
         * 仅 L3 追加 postRotate(绕缓冲中心公转)。三层不透明互相让位拼成马赛克。
         */
        fun drawLayer(angle: Float, ox: Float, oy: Float, orbit: Boolean) {
            val m = Matrix()
            m.setScale(k, k)
            m.postRotate(angle, half, half)
            m.postTranslate(cx + ox, cy + oy)
            if (orbit) m.postRotate(angle, wB / 2f, hB / 2f)
            c.drawBitmap(src, m, paintLayer)
        }

        drawLayer(angle1, 0f, 0f, orbit = false)
        drawLayer(angle2, -0.95f * wB, -0.7f * hB, orbit = false)
        drawLayer(angle3, -0.5f * wB, 0.7f * hB, orbit = true)

        // 附加流光色整幅叠染（模糊前）
        for (color in additionalColors) {
            paintFill.color = color
            c.drawPaint(paintFill)
        }

        wallpaper = blur(buf)
        buf.recycle()
    }

    /** 高斯模糊：椒盐 RenderScript ScriptIntrinsicBlur 半径 25；不可用时退化盒式模糊 */
    private fun blur(bmp: Bitmap): Bitmap {
        // 首选 RenderScript（与椒盐一致的真高斯）
        try {
            val rs = RenderScript.create(context)
            val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val input = Allocation.createFromBitmap(rs, bmp)
            val output = Allocation.createFromBitmap(rs, out)
            val blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            blur.setRadius(blurRadius.coerceAtMost(25f))
            blur.setInput(input)
            blur.forEach(output)
            output.copyTo(out)
            rs.destroy()
            return out
        } catch (_: Throwable) {
            // RenderScript 不可用（新平台/裁剪）：退化盒式模糊
        }
        return boxBlur(bmp)
    }

    /** 备用：两轮盒式模糊近似高斯（仅小位图使用） */
    private fun boxBlur(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val r = min(12, min(w, h) / 2).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val s1 = IntArray(w * h)
        val s2 = IntArray(w * h)
        val half = max(1, r / 2)
        repeat(2) {
            boxPass(pixels, s1, w, h, half, horizontal = true)
            boxPass(s1, s2, w, h, half, horizontal = false)
            s2.copyInto(pixels)
        }
        out.setPixels(s2, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, rad: Int, horizontal: Boolean) {
        val count = rad * 2 + 1
        if (horizontal) {
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    var r = 0L; var g = 0L; var b = 0L; var a = 0L
                    for (k in -rad..rad) {
                        val xx = (x + k).coerceIn(0, w - 1)
                        val p = src[row + xx]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                    }
                    dst[row + x] = ((a / count).toInt() shl 24) or
                        ((r / count).toInt() shl 16) or
                        ((g / count).toInt() shl 8) or
                        (b / count).toInt()
                }
            }
        } else {
            for (x in 0 until w) {
                for (y in 0 until h) {
                    var r = 0L; var g = 0L; var b = 0L; var a = 0L
                    for (k in -rad..rad) {
                        val yy = (y + k).coerceIn(0, h - 1)
                        val p = src[yy * w + x]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                    }
                    dst[y * w + x] = ((a / count).toInt() shl 24) or
                        ((r / count).toInt() shl 16) or
                        ((g / count).toInt() shl 8) or
                        (b / count).toInt()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimators()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && renderActive && dynamic && !reduced) startAnimators()
        else stopAnimators()
    }
}
