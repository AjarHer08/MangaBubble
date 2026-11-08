package com.mangabubble

object Config {
    const val PREFS = "cfg"
    const val DEFAULT_MODEL = "claude-haiku-5-5"

    // (label, kode bahasa ML Kit)
    val sources = listOf(
        "Manga (Jepang)" to "ja",
        "Manhua (Mandarin)" to "zh",
        "Manhwa (Korea)" to "ko",
        "Komik Inggris" to "en"
    )
    val targets = listOf(
        "Indonesia" to "id",
        "English" to "en",
        "Melayu" to "ms"
    )
}
