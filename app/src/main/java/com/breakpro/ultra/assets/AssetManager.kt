package com.breakpro.ultra.assets

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Asset pack format (ZIP):
 *   manifest.json
 *   <any files referenced by the manifest>
 *
 * manifest.json:
 * {
 *   "id": "my-pack",              // [a-z0-9-_], unique
 *   "name": "My Pack",
 *   "version": "1.0",
 *   "license": "CC0-1.0",         // required: licence name/SPDX id
 *   "source": "https://...",      // where the pack came from
 *   "assets": [
 *     { "file": "luts/warm.cube", "type": "LUT", "name": "Warm", "description": "..." }
 *   ]
 * }
 * type: TRANSITION, EFFECT, PRESET, LUT, SFX, MUSIC, OVERLAY, FONT, STICKER, BACKGROUND,
 *       TEMPLATE, BRUSH, VECTOR, MATERIAL (free text is accepted).
 */
data class Asset(
    val packId: String, val file: String, val type: String,
    val name: String, val description: String, val sizeBytes: Long, val path: File
) { val key get() = "$packId/$file" }

data class AssetPack(
    val id: String, val name: String, val version: String,
    val license: String, val source: String, val assets: List<Asset>
)

object AssetManager {
    private const val MAX_TOTAL_BYTES = 300L * 1024 * 1024
    private val idRegex = Regex("[a-z0-9_-]{1,64}")

    private fun root(ctx: Context) = File(ctx.filesDir, "assets").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("asset_favs", Context.MODE_PRIVATE)

    /** Installs a pack from a picked ZIP. Returns the pack or throws with a readable message. */
    fun install(ctx: Context, zip: Uri): AssetPack {
        val tmp = File(ctx.cacheDir, "pack_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            var total = 0L
            ctx.contentResolver.openInputStream(zip)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val out = File(tmp, e.name)
                        // zip-slip protection
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                            throw IllegalArgumentException("Unsafe path in ZIP: ${e.name}")
                        }
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zis.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_TOTAL_BYTES) throw IllegalArgumentException("Pack too large")
                                    os.write(buf, 0, n)
                                }
                            }
                        }
                        e = zis.nextEntry
                    }
                }
            }
            val manifestFile = File(tmp, "manifest.json")
            if (!manifestFile.exists()) throw IllegalArgumentException("manifest.json missing")
            val m = JSONObject(manifestFile.readText())
            val id = m.getString("id")
            if (!idRegex.matches(id)) throw IllegalArgumentException("Invalid pack id '$id'")
            if (m.optString("license").isBlank()) throw IllegalArgumentException("Manifest must declare a license")
            val arr = m.getJSONArray("assets")
            for (i in 0 until arr.length()) {
                val f = File(tmp, arr.getJSONObject(i).getString("file"))
                if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator) || !f.isFile)
                    throw IllegalArgumentException("Asset file missing or invalid: ${f.name}")
            }
            val dest = File(root(ctx), id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) { tmp.copyRecursively(dest, true); tmp.deleteRecursively() }
            return readPack(dest)!!
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }

    fun list(ctx: Context): List<AssetPack> =
        root(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { readPack(it) }?.sortedBy { it.name } ?: emptyList()

    fun deletePack(ctx: Context, packId: String) {
        File(root(ctx), packId).deleteRecursively()
        val p = prefs(ctx)
        p.edit().putStringSet("favs", (p.getStringSet("favs", emptySet()) ?: emptySet()).filterNot { it.startsWith("$packId/") }.toSet()).apply()
    }

    fun isFavorite(ctx: Context, a: Asset) = prefs(ctx).getStringSet("favs", emptySet())?.contains(a.key) == true

    fun toggleFavorite(ctx: Context, a: Asset) {
        val p = prefs(ctx)
        val s = (p.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(a.key)) s.remove(a.key)
        p.edit().putStringSet("favs", s).apply()
    }

    private fun readPack(dir: File): AssetPack? = try {
        val m = JSONObject(File(dir, "manifest.json").readText())
        val id = m.getString("id")
        val arr = m.getJSONArray("assets")
        val assets = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val f = File(dir, o.getString("file"))
            Asset(id, o.getString("file"), o.optString("type", "OTHER").uppercase(),
                o.optString("name", f.nameWithoutExtension), o.optString("description"), f.length(), f)
        }
        AssetPack(id, m.optString("name", id), m.optString("version", "1"),
            m.optString("license"), m.optString("source"), assets)
    } catch (_: Throwable) { null }
}
