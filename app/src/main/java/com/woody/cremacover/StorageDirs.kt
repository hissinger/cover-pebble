package com.woody.cremacover

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.IOException

/** 내장메모리 루트(/sdcard) */
object StorageDirs {
    val root: File get() = Environment.getExternalStorageDirectory()
}

/** 크레마 설정의 사용자 이미지 폴더(/sdcard/Wallpaper)에 표지를 저장한다. */
object ScreenInstaller {
    /** [bitmap] 을 Wallpaper 폴더의 [fileName] 에 덮어써 저장하고 저장된 파일을 돌려준다. */
    @Throws(IOException::class)
    fun install(context: Context, bitmap: Bitmap, fileName: String): File {
        val dir = CremaWallpaper.dir
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("폴더를 만들 수 없습니다: ${dir.path}")

        val target = File(dir, fileName)
        val tmp = File(dir, ".$fileName.tmp")
        tmp.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("이미지 인코딩 실패")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("파일을 저장할 수 없습니다: ${target.path}")
        }

        MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
        return target
    }
}
