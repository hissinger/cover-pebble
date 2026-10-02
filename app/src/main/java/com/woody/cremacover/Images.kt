package com.woody.cremacover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ImageLoader {
    private val thumbCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Throws(IOException::class)
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", CoverSearch.USER_AGENT)
        conn.setRequestProperty("Referer", "https://www.yes24.com/")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** [maxSide] 이하로 줄여서 디코딩한다. */
    fun decode(bytes: ByteArray, maxSide: Int = Int.MAX_VALUE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 리사이클되는 셀에서 쓰기 위해 tag 로 요청을 식별한다. */
    fun loadThumb(scope: CoroutineScope, view: ImageView, url: String) {
        view.tag = url
        thumbCache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        scope.launch {
            val bmp = try {
                withContext(Dispatchers.IO) {
                    val bytes = if (url.startsWith("/")) File(url).readBytes() else download(url)
                    decode(bytes, maxSide = 400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // 썸네일은 못 불러와도 빈칸으로 둔다.
            } ?: return@launch
            thumbCache.put(url, bmp)
            if (view.tag == url) view.setImageBitmap(bmp)
        }
    }
}

/** 표지를 기기 화면 크기에 맞춰 흑백 이미지로 만든다. */
object CoverRenderer {
    fun render(src: Bitmap, width: Int, height: Int, mode: FitMode, blackBackground: Boolean): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (blackBackground) Color.BLACK else Color.WHITE)

        val scale = when (mode) {
            FitMode.FIT -> minOf(width.toFloat() / src.width, height.toFloat() / src.height)
            FitMode.FILL -> maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        }
        val w = src.width * scale
        val h = src.height * scale
        val dst = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }
}
