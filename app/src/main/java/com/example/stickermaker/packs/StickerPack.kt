package com.example.stickermaker.packs

import org.json.JSONArray
import org.json.JSONObject

data class Sticker(
    val fileName: String,
    val emojis: List<String>,
    val caption: String = "",
) {
    companion object {
        /** WhatsApp requires at least one emoji per sticker (used for emoji search); we always attach this one. */
        val DEFAULT_EMOJIS = listOf("😀")
    }
}

/**
 * A WhatsApp sticker pack. WhatsApp requires every sticker in a pack to be the same kind
 * (all static or all animated), 3–30 stickers, 512x512 WebP, and a 96x96 PNG tray icon.
 */
data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    /** Must change whenever the pack's images change, so WhatsApp refreshes its copy. */
    val imageDataVersion: Int,
    val trayFile: String,
    val stickers: List<Sticker>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("publisher", publisher)
        put("animated", animated)
        put("imageDataVersion", imageDataVersion)
        put("trayFile", trayFile)
        put("stickers", JSONArray().apply {
            stickers.forEach { s ->
                put(JSONObject().apply {
                    put("file", s.fileName)
                    put("emojis", JSONArray(s.emojis))
                    put("caption", s.caption)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): StickerPack {
            val arr = o.getJSONArray("stickers")
            val stickers = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val emojiArr = s.optJSONArray("emojis") ?: JSONArray()
                Sticker(
                    fileName = s.getString("file"),
                    emojis = (0 until emojiArr.length()).map { emojiArr.getString(it) },
                    caption = s.optString("caption"),
                )
            }
            return StickerPack(
                id = o.getString("id"),
                name = o.getString("name"),
                publisher = o.getString("publisher"),
                animated = o.getBoolean("animated"),
                imageDataVersion = o.getInt("imageDataVersion"),
                trayFile = o.getString("trayFile"),
                stickers = stickers,
            )
        }
    }
}
