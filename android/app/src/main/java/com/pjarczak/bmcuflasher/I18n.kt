package com.pjarczak.bmcuflasher

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale

class I18n(ctx: Context) {
  private val map: JSONObject
  private val fallback = JSONObject(ctx.assets.open("en.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

  init {
    val assets = ctx.assets
    val available = (assets.list("") ?: emptyArray())
      .filter { it.endsWith(".json", ignoreCase = true) }
      .map { it.removeSuffix(".json").lowercase(Locale.ROOT) }
      .toSet()

    val lang = detectLang(ctx)
    val file = if (lang in available) "$lang.json" else "en.json"
    val s = assets.open(file).bufferedReader(Charsets.UTF_8).readText()
    map = JSONObject(s)
  }

  private fun detectLang(ctx: Context): String {
    val raw = if (Build.VERSION.SDK_INT >= 24) {
      ctx.resources.configuration.locales[0]?.language
    } else {
      @Suppress("DEPRECATION")
      ctx.resources.configuration.locale?.language
    } ?: Locale.getDefault().language

    return when (raw.lowercase(Locale.ROOT)) {
      "ja" -> "jp"
      else -> raw.lowercase(Locale.ROOT)
    }
  }

  fun t(k: String): String {
    val v = map.optString(k, "")
    return if (v.isNotEmpty()) v else fallback.optString(k, k)
  }
}
