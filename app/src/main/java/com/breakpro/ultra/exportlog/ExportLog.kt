package com.breakpro.ultra.exportlog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ExportEntry(val time: Long, val kind: String, val detail: String, val sizeBytes: Long)

/** Tiny persistent history of finished exports (last 100). sizeBytes = 0 means unknown. */
object ExportLog {
    private const val MAX = 100
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("export_log", Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, kind: String, detail: String, sizeBytes: Long = 0) {
        try {
            val arr = JSONArray(prefs(ctx).getString("items", "[]"))
            val o = JSONObject().put("t", System.currentTimeMillis()).put("k", kind)
                .put("d", detail).put("s", sizeBytes)
            val out = JSONArray().put(o)
            for (i in 0 until minOf(arr.length(), MAX - 1)) out.put(arr.get(i))
            prefs(ctx).edit().putString("items", out.toString()).apply()
        } catch (_: Throwable) { }
    }

    fun list(ctx: Context): List<ExportEntry> = try {
        val arr = JSONArray(prefs(ctx).getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ExportEntry(o.getLong("t"), o.getString("k"), o.optString("d"), o.optLong("s"))
        }
    } catch (_: Throwable) { emptyList() }

    fun clear(ctx: Context) { prefs(ctx).edit().remove("items").apply() }
}
