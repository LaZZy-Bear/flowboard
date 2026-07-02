package com.flowboard.ime.data.models

/**
 * Thai character classification tags used by the Scoring Engine
 * for pattern penalty analysis and context-aware predictions.
 *
 * Categories:
 * - C  = Consonant (พยัญชนะ)
 * - Vp = Prefix Vowel (สระหน้า: เ แ โ ใ ไ)
 * - Vf = Follow Vowel (สระหลัง: ะ า ำ ๅ)
 * - Vt = Top Vowel (สระบน: ิ ี ึ ื ็ ั ํ)
 * - Vb = Bottom Vowel (สระล่าง: ุ ู)
 * - T  = Tone Mark (วรรณยุกต์: ่ ้ ๊ ๋ ์)
 * - O  = Other (อื่นๆ: ๆ ฯ ฿)
 * - S  = Space (เว้นวรรค)
 */
enum class CharTag(val code: String) {
    C("C"),
    Vp("Vp"),
    Vf("Vf"),
    Vt("Vt"),
    Vb("Vb"),
    T("T"),
    O("O"),
    S("S");

    companion object {
        private val codeMap = entries.associateBy { it.code }

        /**
         * Get the CharTag for a given character using the loaded char map.
         * Falls back to [O] if the character is not found.
         */
        fun fromChar(c: Char, charMap: Map<String, String>): CharTag {
            if (c == ' ') return S
            val code = charMap[c.toString()] ?: return O
            return codeMap[code] ?: O
        }

        /**
         * Get CharTag from its code string.
         */
        fun fromCode(code: String): CharTag {
            return codeMap[code] ?: O
        }
    }
}
