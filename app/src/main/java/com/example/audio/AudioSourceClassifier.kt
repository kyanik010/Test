package com.example.audio

/**
 * Strict classifier for beIN SPORTS and Alwan IPTV audio sources.
 *
 * Normalizes Arabic diacritics, character variants, and English transliterations
 * to accurately identify valid audio channel sources while preventing false positives.
 */
object AudioSourceClassifier {

    private val TASHKEEL_REGEX = Regex("[\\u064B-\\u065F\\u0670]")
    private val PUNCTUATION_REGEX = Regex("[^a-z0-9\\u0600-\\u06FF]+")

    private val BEIN_ENGLISH_PATTERNS = listOf(
        "bein sports",
        "bein sport",
        "beinsports",
        "beinsport",
        "be in sports",
        "be in sport"
    )

    private val BEIN_ARABIC_PATTERNS = listOf(
        "بي ان سبورت",
        "بي ان",
        "بين سبورت",
        "بين سبورتس",
        "بي إن سبورت",
        "بيان سبورت"
    )

    private val ALWAN_PATTERNS = listOf(
        "alwan",
        "al wan",
        "elwan",
        "الوان",
        "ألوان"
    )

    // Explicit exclusions that must never be classified as beIN or Alwan
    private val EXCLUSION_PATTERNS = listOf(
        "abu dhabi sports",
        "abudhabi sports",
        "ad sports",
        "ابو ظبي الرياضيه",
        "ابوظبي الرياضية",
        "ssc sport",
        "ssc sports",
        "alkass",
        "الكاس",
        "on time sports",
        "اون تايم سبورت",
        "dubai sports",
        "دبي الرياضية",
        "sharjah sports",
        "الشارقة الرياضية"
    )

    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.trim()
            .replace(TASHKEEL_REGEX, "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ة", "ه")
            .replace("ى", "ي")
            .lowercase()
            .replace(PUNCTUATION_REGEX, " ")
            .trim()
    }

    fun classify(name: String?, categoryName: String? = null, categoryId: String? = null): AudioChannelGroup {
        val normName = normalize(name)
        val normCategory = normalize(categoryName)
        val combined = "$normName $normCategory ${normalize(categoryId)}".trim()

        if (combined.isEmpty()) return AudioChannelGroup.OTHER

        // Check exclusions first (e.g. AD Sports mentioning another event)
        for (exclusion in EXCLUSION_PATTERNS) {
            if (combined.contains(exclusion)) {
                // If it explicitly belongs to an excluded network and doesn't explicitly have beIN in channel name
                if (!containsBeIn(normName)) {
                    return AudioChannelGroup.OTHER
                }
            }
        }

        if (containsBeIn(combined)) {
            return AudioChannelGroup.BEIN_SPORTS
        }

        if (containsAlwan(combined)) {
            return AudioChannelGroup.ALWAN
        }

        return AudioChannelGroup.OTHER
    }

    private fun containsBeIn(text: String): Boolean {
        for (pattern in BEIN_ENGLISH_PATTERNS) {
            if (text.contains(pattern)) return true
        }
        for (pattern in BEIN_ARABIC_PATTERNS) {
            if (text.contains(pattern)) return true
        }
        return false
    }

    private fun containsAlwan(text: String): Boolean {
        for (pattern in ALWAN_PATTERNS) {
            val normalizedPattern = normalize(pattern)
            if (Regex("\\b$normalizedPattern\\b").containsMatchIn(text) || text.contains(normalizedPattern)) {
                return true
            }
        }
        return false
    }

    fun isAudioCandidate(name: String?, categoryName: String? = null, categoryId: String? = null): Boolean {
        return classify(name, categoryName, categoryId) != AudioChannelGroup.OTHER
    }
}
