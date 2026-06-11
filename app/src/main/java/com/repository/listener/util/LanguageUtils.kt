package com.repository.listener.util

/**
 * Maps ISO 639-1 language codes (and TranslationConfigDialog display strings)
 * to NLLB-200 language codes used by the translation pipeline.
 *
 * The canonical language list lives in TranslationConfigDialog.Language; this
 * object provides a standalone lookup so callers (ListenerService, ADB
 * commands, glasses relay) can derive NLLB codes without pulling in the UI
 * dialog class.
 */
object LanguageUtils {

    private val codeToNllb = mapOf(
        "en" to "eng_Latn",
        "ru" to "rus_Cyrl",
        "zh" to "zho_Hans",
        "ja" to "jpn_Jpan",
        "ko" to "kor_Hang",
        "fr" to "fra_Latn",
        "de" to "deu_Latn",
        "es" to "spa_Latn",
        "it" to "ita_Latn",
        "pt" to "por_Latn",
        "ar" to "ara_Arab",
        "hi" to "hin_Deva",
        "tr" to "tur_Latn",
        "vi" to "vie_Latn",
        "th" to "tha_Thai",
        "id" to "ind_Latn",
        "nl" to "nld_Latn",
        "pl" to "pol_Latn",
        "uk" to "ukr_Cyrl",
        "cs" to "ces_Latn",
        "sv" to "swe_Latn",
        "el" to "ell_Grek",
        "he" to "heb_Hebr",
        "ro" to "ron_Latn",
        "hu" to "hun_Latn",
    )

    private const val FALLBACK = "eng_Latn"

    /**
     * Resolve an NLLB code from a language short code (e.g. "en" -> "eng_Latn").
     * Accepts both lower and upper case input. Returns [FALLBACK] for unknown codes.
     */
    fun languageToNllb(langCode: String): String {
        return codeToNllb[langCode.lowercase()] ?: FALLBACK
    }

    /**
     * Return [nllbCode] if non-empty, otherwise derive from [langCode].
     * Convenience for the common pattern where NLLB may or may not be provided.
     */
    fun resolveNllb(nllbCode: String, langCode: String): String {
        return nllbCode.ifEmpty { languageToNllb(langCode) }
    }
}
