package com.mangabubble

object Config {
    const val PREFS = "cfg"
    const val DEFAULT_MODEL = "claude-sonnet-5-5"   // lebih akurat; claude-haiku-5-5 lebih murah dan cepat
    const val REPO = "AjarHer08/MangaBubble"        // sumber rilis untuk cek pembaruan

    val engines = listOf(
        "Claude AI (paling akurat, butuh API key)",
        "Google Translate (gratis, tanpa key)",
        "Bawaan HP (offline, kurang akurat)"
    )

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
