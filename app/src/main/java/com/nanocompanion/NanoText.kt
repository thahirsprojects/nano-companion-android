package com.nanocompanion

import java.text.Normalizer

// Nano's OLED font only covers ASCII, and '|' separates protocol fields.
object NanoText {

    private val replacements = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'",
        '“' to "\"", '”' to "\"", '„' to "\"",
        '–' to "-", '—' to "-", '…' to "...",
        ' ' to " ", '|' to "/", '\r' to " ", '\n' to " ", '\t' to " "
    )

    private val combiningMarks = Regex("\\p{Mn}+")
    private val spaces = Regex(" {2,}")

    fun clean(input: String, maxLength: Int): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(combiningMarks, "")

        val out = StringBuilder(decomposed.length)

        for (ch in decomposed) {
            val mapped = replacements[ch]

            when {
                mapped != null -> out.append(mapped)
                ch.code in 0x20..0x7E -> out.append(ch)
                // Emoji and other symbols are dropped.
            }
        }

        return out.toString()
            .replace(spaces, " ")
            .trim()
            .take(maxLength)
            .trim()
    }
}
