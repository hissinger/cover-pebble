package com.woody.cremacover

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기기가 슬립·종료 이미지를 어떻게 저장·선택하는지 adb 없이 알아내기 위한 조사 도구.
 * 결과는 진단 화면에 보여주고 Download 폴더에 텍스트로 남긴다.
 */
object DeviceProbe {
    private val KEYWORDS = Regex("sleep|wallpaper|screensaver|standby|power_?off|shutdown|suspend|lock_?screen|epd", RegexOption.IGNORE_CASE)
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "bmp", "webp")

    /** system/secure/global 설정 중 키나 값에 키워드가 들어간 항목 */
    fun matchingSettings(context: Context): List<String> =
        listOf("system" to Settings.System.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI, "global" to Settings.Global.CONTENT_URI)
            .flatMap { (table, uri) -> readTable(context, uri).map { (k, v) -> "$table/$k = $v" } }
            .filter { KEYWORDS.containsMatchIn(it) }

    private fun readTable(context: Context, uri: Uri): List<Pair<String, String?>> = runCatching {
        context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** 내장메모리에서 [sinceMillis] 이후 수정된 이미지 파일 (최신순) */
    fun recentImages(sinceMillis: Long): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 6) return
            dir.listFiles()?.forEach { f ->
                when {
                    f.isDirectory -> if (f.name != "Android") walk(f, depth + 1)
                    f.extension.lowercase() in IMAGE_EXT && f.lastModified() >= sinceMillis -> result += f
                }
            }
        }
        walk(StorageDirs.root, 0)
        return result.sortedByDescending { it.lastModified() }
    }

    /** /data 등 앱이 읽을 수 있는 곳에 흔히 쓰이는 대기·종료 이미지 경로가 있는지 */
    fun knownSystemImages(): List<String> =
        listOf("/data/misc", "/data/local", "/data/system", "/system/media", "/vendor/media", "/oem")
            .map(::File)
            .flatMap { dir -> dir.listFiles()?.filter { KEYWORDS.containsMatchIn(it.name) || it.extension in IMAGE_EXT }.orEmpty() }
            .map { "${it.path} (${it.length()}B, ${formatTime(it.lastModified())})" }

    fun report(context: Context, sinceMillis: Long): String = buildString {
        appendLine("[기기] ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
        appendLine()
        appendLine("[설정값]")
        matchingSettings(context).ifEmpty { listOf("(없음)") }.forEach(::appendLine)
        appendLine()
        appendLine("[최근 바뀐 이미지 · ${formatTime(sinceMillis)} 이후]")
        recentImages(sinceMillis).take(30).ifEmpty { null }?.forEach {
            appendLine("${it.path} (${it.length()}B, ${formatTime(it.lastModified())})")
        } ?: appendLine("(없음)")
        appendLine()
        appendLine("[시스템 이미지 후보]")
        knownSystemImages().ifEmpty { listOf("(없음 또는 접근 불가)") }.forEach(::appendLine)
    }

    fun formatTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** 슬립·종료 화면을 다룰 법한 시스템 앱 */
    private val EXPORT_KEYWORDS = Regex("wetao|crema|yes24|into1|innowave|logo|standby|settings|launcher|epd|eink", RegexOption.IGNORE_CASE)

    data class ExportResult(val dir: File, val copied: List<String>, val failed: List<String>)

    /**
     * 키워드에 맞는 시스템 앱의 APK 를 Download/cremacover_apks 로 복사하고,
     * 설치된 전체 앱 목록과 각 앱의 서비스·리시버·프로바이더 요약을 packages.txt 로 남긴다.
     * Mac 에서 APK 를 디컴파일해 슬립 이미지 처리 방식을 분석하기 위한 것.
     */
    fun exportSystemApks(context: Context): ExportResult {
        val pm = context.packageManager
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "cremacover_apks")
        out.mkdirs()
        val apps = pm.getInstalledApplications(0).sortedBy { it.packageName }
        val targets = apps.filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && EXPORT_KEYWORDS.containsMatchIn(it.packageName) }

        val copied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (app in targets) {
            val sources = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
            sources.forEachIndexed { i, path ->
                val name = if (i == 0) "${app.packageName}.apk" else "${app.packageName}_split$i.apk"
                runCatching { File(path).copyTo(File(out, name), overwrite = true) }
                    .onSuccess { copied += "$name (${it.length() / 1024}KB)" }
                    .onFailure { failed += "$name: ${it.message}" }
            }
        }

        val summary = buildString {
            appendLine("[내보낸 앱]")
            targets.forEach { appendLine("${it.packageName}  ${it.sourceDir}") }
            appendLine()
            appendLine("[컴포넌트]")
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES
            targets.forEach { app ->
                val info = runCatching { pm.getPackageInfo(app.packageName, flags) }.getOrNull() ?: return@forEach
                appendLine("# ${app.packageName}")
                fun dump(kind: String, list: Array<out ComponentInfo>?) = list.orEmpty().forEach {
                    appendLine("  $kind ${it.name} exported=${it.exported}")
                }
                dump("activity", info.activities)
                dump("service", info.services)
                dump("receiver", info.receivers)
                info.providers.orEmpty().forEach { appendLine("  provider ${it.name} authority=${it.authority} exported=${it.exported}") }
            }
            appendLine()
            appendLine("[설치된 전체 앱]")
            apps.forEach { appendLine("${it.packageName}  ${it.sourceDir}") }
        }
        File(out, "packages.txt").writeText(summary)
        return ExportResult(out, copied, failed)
    }
}
