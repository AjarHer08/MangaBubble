package com.mangabubble

object Config {
    const val PREFS = "cfg"

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
