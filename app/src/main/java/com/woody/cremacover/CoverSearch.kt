package com.woody.cremacover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

data class BookCover(
    val id: String,
    val title: String,
    val author: String,
    val publisher: String,
    val thumbUrl: String,
    val coverUrl: String,
)

/** YES24 검색 결과 페이지에서 책 표지를 찾는다. 크레마와 같은 YES24 표지라 eBook 표지와도 대부분 일치한다. */
object CoverSearch {
    // YES24 는 오래된 Chrome UA 를 메인 페이지로 리다이렉트하므로 Safari UA 를 쓴다.
    const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    private val EXCLUDED_TYPES = setOf("[중고도서]", "[Blu-ray]", "[DVD]", "[음반]", "[LP]", "[GIFT]")
    private val GOODS_ID = Regex("""/goods/(\d+)""")

    suspend fun search(query: String): List<BookCover> = withContext(Dispatchers.IO) {
        val url = "https://www.yes24.com/Product/Search?domain=ALL&query=" +
            URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15_000).get()
        if (!doc.location().contains("/Search", ignoreCase = true)) {
            throw IOException("YES24 검색 페이지 대신 ${doc.location()} 로 이동했습니다")
        }

        doc.select("div.itemUnit").mapNotNull { item ->
            val type = item.selectFirst("span.gd_res")?.text()?.trim()
            if (type != null && type in EXCLUDED_TYPES) return@mapNotNull null

            val nameLink = item.selectFirst("a.gd_name") ?: return@mapNotNull null
            val id = GOODS_ID.find(nameLink.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            BookCover(
                id = id,
                title = nameLink.text().trim(),
                author = item.select("span.info_auth a").joinToString(", ") { it.text().trim() },
                publisher = item.selectFirst("span.info_pub a")?.text()?.trim().orEmpty(),
                thumbUrl = "https://image.yes24.com/goods/$id/L",
                coverUrl = "https://image.yes24.com/goods/$id/XL",
            )
        }.distinctBy { it.id }
    }
}
