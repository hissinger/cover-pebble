package com.woody.cremacover

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 찾아둔 표지 보관함. 원본 표지 이미지를 앱 전용 저장소에 두고 목록은 JSON 으로 관리한다. */
class SavedCovers(context: Context) {
    private val dir = File(context.filesDir, "covers").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun list(): List<BookCover> {
        if (!index.exists()) return emptyList()
        val arr = runCatching { JSONArray(index.readText()) }.getOrElse { return emptyList() }
        return (0 until arr.length()).map { arr.getJSONObject(it).toCover() }
            .filter { localImage(it.id) != null }
    }

    fun contains(id: String) = list().any { it.id == id }

    fun imageFile(id: String) = File(dir, "$id.jpg")

    /** 비어 있지 않은 보관 이미지. 쓰다가 끊긴 파일은 없는 것으로 본다. */
    fun localImage(id: String): File? = imageFile(id).takeIf { it.length() > 0 }

    /** 최근에 담은 것이 맨 앞에 오도록 저장한다. */
    fun add(cover: BookCover, imageBytes: ByteArray) {
        writeAtomically(imageFile(cover.id)) { it.writeBytes(imageBytes) }
        write(listOf(cover) + list().filter { it.id != cover.id })
    }

    fun remove(id: String) {
        imageFile(id).delete()
        write(list().filter { it.id != id })
    }

    private fun write(covers: List<BookCover>) {
        val arr = JSONArray()
        covers.forEach { arr.put(it.toJson()) }
        writeAtomically(index) { it.writeText(arr.toString()) }
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        write(tmp)
        if (!tmp.renameTo(target)) tmp.delete()
    }

    private fun BookCover.toJson() = JSONObject()
        .put("id", id).put("title", title).put("author", author).put("publisher", publisher)
        .put("thumbUrl", thumbUrl).put("coverUrl", coverUrl)

    private fun JSONObject.toCover() = BookCover(
        id = getString("id"),
        title = optString("title"),
        author = optString("author"),
        publisher = optString("publisher"),
        thumbUrl = optString("thumbUrl"),
        coverUrl = optString("coverUrl"),
    )
}
