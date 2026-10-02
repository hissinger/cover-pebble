package com.woody.cremacover

import android.content.Context

enum class FitMode { FIT, FILL }

/** 앱 설정 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fitMode: FitMode
        get() = FitMode.valueOf(sp.getString(KEY_FIT, FitMode.FIT.name)!!)
        set(v) = sp.edit().putString(KEY_FIT, v.name).apply()

    /** FIT 모드에서 남는 여백 색. true = 검정, false = 흰색 */
    var blackBackground: Boolean
        get() = sp.getBoolean(KEY_BLACK_BG, false)
        set(v) = sp.edit().putBoolean(KEY_BLACK_BG, v).apply()

    companion object {
        /** 크레마 설정은 고른 이미지를 270° 돌려 1448x1072 로 맞추므로, 세로 1072x1448 로 만든다. */
        const val WIDTH = 1072
        const val HEIGHT = 1448

        private const val KEY_FIT = "fit_mode"
        private const val KEY_BLACK_BG = "black_bg"
    }
}
