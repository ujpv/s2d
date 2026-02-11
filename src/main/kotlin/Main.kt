import sun.java2d.metal.MTLGraphics2D
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.image.VolatileImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.RepaintManager
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

object SkiaTextureDemo {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("sun.java2d.metal", "true")

        SwingUtilities.invokeLater {
            val frame = JFrame("Skia-on-Metal Texture via RenderingTask")
            RepaintManager.currentManager(frame).setDoubleBufferingEnabled(false)
            frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            frame.contentPane = SkiaPanel()
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
    }

    internal class SkiaPanel : JPanel() {
        init {
            preferredSize = Dimension(640, 400)
            background = Color(0x22, 0x22, 0x22)
            addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    repaint()
                }
            })
        }


        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (width <= 0 || height <= 0) return

            val g2 = g as? Graphics2D ?: return
            // Background and title drawn with Java2D for clarity
            g2.color = background
            g2.fillRect(0, 0, width, height)
            g2.color = Color.WHITE
            g2.font = Font("SansSerif", Font.PLAIN, 24)
            g2.drawString("The text is rendered via Java2D(before skia)", 18, 36)

            val ok = withSkiaCanvas(g2, width, height) { canvas, _, scale ->
                drawSkiaDemo(canvas, width, height, scale)
            }
            if (!ok) {
                g2.color = Color.RED
                g2.font = Font("SansSerif", Font.PLAIN, 14)
                g2.drawString("MTLGraphics2D.runExternal failed (not a Metal surface)", 18, 52)
            }

            g2.color = Color.WHITE
            g2.font = Font("SansSerif", Font.PLAIN, 24)
            g2.drawString("The text is rendered via Java2D(after skia)", 18, height - 20)
        }
    }

    fun withSkiaCanvas(
        g2d: Graphics2D,
        width: Int,
        height: Int,
        block: (org.jetbrains.skia.Canvas, org.jetbrains.skia.DirectContext, Float) -> Unit
    ): Boolean {
        return MTLGraphics2D.runExternal(g2d, object : RenderingTask {
            override fun run(surfaceType: String?, pointers: List<Long>, names: List<String?>) {
                val device = pointers[RenderingTask.MTL_DEVICE_ARG_INDEX]
                val queue = pointers[RenderingTask.MTL_COMMAND_QUEUE_ARG_INDEX]
                val texture = pointers[RenderingTask.MTL_TEXTURE_ARG_INDEX]
                if (device == 0L || queue == 0L || texture == 0L) return

                val gc = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
                val scale = gc.defaultTransform.scaleX.toFloat()

                val physicalWidth = (width * scale).toInt().coerceAtLeast(1)
                val physicalHeight = (height * scale).toInt().coerceAtLeast(1)

                val grCtx = org.jetbrains.skia.DirectContext.makeMetal(device, queue) ?: return
                val backendRT = org.jetbrains.skia.BackendRenderTarget.makeMetal(physicalWidth, physicalHeight, texture)
                val surface = org.jetbrains.skia.Surface.makeFromBackendRenderTarget(
                    grCtx,
                    backendRT,
                    org.jetbrains.skia.SurfaceOrigin.TOP_LEFT,
                    org.jetbrains.skia.SurfaceColorFormat.BGRA_8888,
                    null,
                    null
                ) ?: return

                val canvas = surface.canvas
                canvas.scale(scale, scale)

                block(canvas, grCtx, scale)

                grCtx.flushAndSubmit(surface)
            }
        })
    }

    // Demo drawing reused by the panel
    private fun drawSkiaDemo(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, scale: Float) {
        val logicalWidth = width.toFloat()
        val logicalHeight = height.toFloat()

        // Background: diagonal gradient
        val bgColors = intArrayOf(
            org.jetbrains.skia.Color.makeARGB(140, 24, 28, 44),
            org.jetbrains.skia.Color.makeARGB(140, 10, 12, 18)
        )
        val bg = org.jetbrains.skia.Shader.makeLinearGradient(
            0f, 0f, logicalWidth, logicalHeight,
            bgColors, null, org.jetbrains.skia.GradientStyle.DEFAULT
        )
        val bgPaint = org.jetbrains.skia.Paint().apply {
            shader = bg
            blendMode = org.jetbrains.skia.BlendMode.SRC_OVER
        }
        canvas.drawPaint(bgPaint)

        // Soft glow circle (radial gradient)
        val cx = logicalWidth * 0.5f
        val cy = logicalHeight * 0.45f
        val rad = kotlin.math.sqrt(logicalWidth * logicalWidth + logicalHeight * logicalHeight) * 0.25f
        val glowColors = intArrayOf(
            org.jetbrains.skia.Color.makeARGB(130, 46, 160, 220),
            org.jetbrains.skia.Color.makeARGB(5, 46, 160, 220)
        )
        val glow = org.jetbrains.skia.Shader.makeRadialGradient(
            cx, cy, rad,
            glowColors, null, org.jetbrains.skia.GradientStyle.DEFAULT
        )
        val glowPaint = org.jetbrains.skia.Paint().apply { shader = glow }
        canvas.drawCircle(cx, cy, rad, glowPaint)

        // Sweeping neon arcs with soft shadow
        val arcBounds1 = org.jetbrains.skia.Rect.makeLTRB(cx - rad * 0.9f, cy - rad * 0.7f, cx + rad * 0.9f, cy + rad * 0.7f)
        val arcBounds2 = org.jetbrains.skia.Rect.makeLTRB(cx - rad * 0.6f, cy - rad * 0.9f, cx + rad * 0.6f, cy + rad * 0.9f)

        val arcShadow = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
            strokeWidth = 18f
            color = org.jetbrains.skia.Color.makeARGB(60, 0, 0, 0)
            imageFilter = org.jetbrains.skia.ImageFilter.makeBlur(6f, 6f, org.jetbrains.skia.FilterTileMode.CLAMP)
        }
        canvas.save()
        canvas.drawArc(arcBounds1.left, arcBounds1.top, arcBounds1.right, arcBounds1.bottom, 210f, 220f, false, arcShadow)
        canvas.drawArc(arcBounds2.left, arcBounds2.top, arcBounds2.right, arcBounds2.bottom, 40f, 230f, false, arcShadow)
        canvas.restore()

        val arcCols1 = intArrayOf(
            org.jetbrains.skia.Color.makeARGB(160, 0, 208, 130),
            org.jetbrains.skia.Color.makeARGB(160, 0, 140, 255)
        )
        val arcGrad1 = org.jetbrains.skia.Shader.makeLinearGradient(
            arcBounds1.left, arcBounds1.bottom, arcBounds1.right, arcBounds1.top,
            arcCols1, null, org.jetbrains.skia.GradientStyle.DEFAULT
        )
        val arcPaint1 = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
            strokeWidth = 10f
            shader = arcGrad1
        }
        canvas.drawArc(arcBounds1.left, arcBounds1.top, arcBounds1.right, arcBounds1.bottom, 210f, 220f, false, arcPaint1)

        val arcCols2 = intArrayOf(
            org.jetbrains.skia.Color.makeARGB(160, 255, 170, 0),
            org.jetbrains.skia.Color.makeARGB(160, 255, 64, 64)
        )
        val arcGrad2 = org.jetbrains.skia.Shader.makeLinearGradient(
            arcBounds2.left, arcBounds2.top, arcBounds2.right, arcBounds2.bottom,
            arcCols2, null, org.jetbrains.skia.GradientStyle.DEFAULT
        )
        val arcPaint2 = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND
            strokeWidth = 10f
            shader = arcGrad2
        }
        canvas.drawArc(arcBounds2.left, arcBounds2.top, arcBounds2.right, arcBounds2.bottom, 40f, 230f, false, arcPaint2)

        // Framed rounded rect card with stroke + translucent fill
        val pad = 24f
        val card = org.jetbrains.skia.Rect.makeLTRB(pad, pad, logicalWidth - pad, logicalHeight - pad)
        val cardFill = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            color = org.jetbrains.skia.Color.makeARGB(40, 255, 255, 255)
        }
        val rrect = org.jetbrains.skia.RRect.makeXYWH(pad, pad, logicalWidth - 2*pad, logicalHeight - 2*pad, 20f, 20f)
        canvas.drawRRect(rrect, cardFill)

        val cardStroke = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            color = org.jetbrains.skia.Color.makeARGB(120, 255, 255, 255)
            mode = org.jetbrains.skia.PaintMode.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRRect(rrect, cardStroke)

        // Title with shadow
        val textShadow = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            color = org.jetbrains.skia.Color.makeARGB(180, 255, 255, 255)
            imageFilter = org.jetbrains.skia.ImageFilter.makeDropShadow(0f, 2f, 4f, 4f, org.jetbrains.skia.Color.makeARGB(100, 0, 0, 0), null)
        }
        val font = org.jetbrains.skia.Font().apply { size = 18f }
        canvas.drawString("Skia + Metal", 36f, 44f, font, textShadow)

        val subtitlePaint = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
            color = org.jetbrains.skia.Color.makeARGB(180, 210, 225, 240)
        }
        val subFont = org.jetbrains.skia.Font().apply { size = 13f }
        canvas.drawString("Rendered into AWT's Metal texture via RenderingTask (Transparent)", 36f, 66f, subFont, subtitlePaint)
    }
}
