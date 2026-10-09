package com.example.mymusic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.security.MessageDigest
import kotlin.math.sin

/** Illustrated covers rendered once on the IO dispatcher and reused by every player and notification. */
class BookArtwork(context: Context) {
    private val directory = File(context.filesDir, "book-illustrations").apply { mkdirs() }

    fun ensure(book: Audiobook): String {
        val signature = "v2:${book.key}:${book.title}:${book.author}"
        val hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        val name = hash.take(16).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(420, 588, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val palette = arrayOf(intArrayOf(0xFF183F43.toInt(), 0xFF6EA39A.toInt()),
                intArrayOf(0xFF542D46.toInt(), 0xFFD99586.toInt()), intArrayOf(0xFF1B2D57.toInt(), 0xFF849DCF.toInt()),
                intArrayOf(0xFF61452E.toInt(), 0xFFDAB47D.toInt()), intArrayOf(0xFF283E32.toInt(), 0xFFA4B28A.toInt()),
                intArrayOf(0xFF462F63.toInt(), 0xFFB59EC9.toInt()), intArrayOf(0xFF64403A.toInt(), 0xFFE3A66E.toInt()))
            val colors = palette[(hash[0].toInt() and 255) % palette.size]
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 420f, 588f, colors[0], colors[1], Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 420f, 588f, paint)
            paint.shader = null
            val cream = 0xFFF5E6C7.toInt()
            paint.color = cream; paint.alpha = 150
            when ((hash[1].toInt() and 255) % 5) {
                0 -> { // Moon and layered mountain silhouettes.
                    canvas.drawCircle(318f, 272f, 57f, paint)
                    repeat(4) { layer ->
                        paint.color = if (layer % 2 == 0) colors[0] else cream; paint.alpha = 65 + layer * 22
                        val path = Path().apply {
                            moveTo(0f, 590f); lineTo(0f, 410f + layer * 32)
                            lineTo(95f, 312f + layer * 47); lineTo(205f, 425f + layer * 20)
                            lineTo(320f, 336f + layer * 39); lineTo(420f, 415f + layer * 23); lineTo(420f, 590f); close()
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                1 -> { // Art-deco arches.
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
                    repeat(9) { i -> canvas.drawRoundRect(48f + i * 13, 245f + i * 12, 372f - i * 13, 675f,
                        160f - i * 12, 160f - i * 12, paint) }
                    paint.style = Paint.Style.FILL
                }
                2 -> { // Constellation with deliberately fixed points.
                    val points = listOf(70f to 350f, 165f to 285f, 236f to 380f, 340f to 315f, 298f to 480f, 148f to 516f)
                    paint.strokeWidth = 1.5f; paint.alpha = 110
                    points.zipWithNext().forEach { (a, b) -> canvas.drawLine(a.first, a.second, b.first, b.second, paint) }
                    points.forEachIndexed { i, p -> canvas.drawCircle(p.first, p.second, if (i % 2 == 0) 6f else 3f, paint) }
                    repeat(22) { i -> canvas.drawCircle(34f + ((i * 73) % 350), 260f + ((i * 47) % 280), 1.5f, paint) }
                }
                3 -> { // Sun and flowing waves.
                    canvas.drawCircle(210f, 350f, 80f, paint)
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.alpha = 100
                    repeat(10) { row ->
                        val path = Path()
                        for (x in 0..420 step 7) {
                            val y = 440f + row * 15 + sin(x / 70f + row * .25f) * 18
                            if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
                        }
                        canvas.drawPath(path, paint)
                    }
                    paint.style = Paint.Style.FILL
                }
                else -> { // Botanical branch.
                    paint.strokeWidth = 3f
                    canvas.drawLine(200f, 570f, 230f, 275f, paint)
                    repeat(7) { i ->
                        val y = 320f + i * 33
                        canvas.save(); canvas.rotate(if (i % 2 == 0) -30f else 30f, 215f, y)
                        canvas.drawOval(if (i % 2 == 0) 112f else 215f, y - 20, if (i % 2 == 0) 216f else 320f, y + 18, paint)
                        canvas.restore()
                    }
                }
            }
            paint.alpha = 255; paint.color = cream; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f
            canvas.drawRect(22f, 22f, 398f, 566f, paint)
            paint.style = Paint.Style.FILL
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = cream; textSize = 17f; letterSpacing = .12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
            drawText(canvas, book.author.uppercase(), text, 328, 46f, 44f, 2)
            text.textSize = 34f; text.letterSpacing = 0f; text.typeface = Typeface.create("serif", Typeface.NORMAL)
            drawText(canvas, book.title, text, 328, 46f, 98f, 4)
            paint.color = Color.BLACK; paint.alpha = 32
            canvas.drawRect(0f, 0f, 15f, 588f, paint)
            val temporary = File(directory, "$name.tmp")
            try {
                temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(temporary.renameTo(file)) { "Cannot cache book illustration" }
            } finally { bitmap.recycle(); temporary.delete() }
        }
        return Uri.fromFile(file).toString()
    }

    private fun drawText(canvas: Canvas, text: String, paint: TextPaint, width: Int, x: Float, y: Float, lines: Int) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(lines).setEllipsize(android.text.TextUtils.TruncateAt.END).setIncludePad(false).build()
        canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
    }
}

fun bookCoverUri(book: Audiobook, details: BookDetails?, generated: Map<String, String>): String? =
    book.chapters.firstNotNullOfOrNull { it.artUri } ?: details?.coverUri ?: generated[book.key]
