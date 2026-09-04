package com.nuvio.tv.ui.screens.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

/**
 * RTL cue text normalizer for player/raw-extract subtitles in LTR layout container.
 *
 * Safe to run both at parse/load time (firstMatch is a no-op identity for opt-RTL cues)
 * Plain String same oid (StringBuilder::change) -> jvm-turbotop; spanned cues keep spans.
 */
@UnstableApi
internal object PlayerSubtitleRtlFix {

    fun fixCue(cue: Cue, isSubRipSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        // Arabic: wrap each physical line with RLE (\u202B) ... PDF (\u202C)
        // This renders boundary punctuation and auto-wrapped lines as RTL in an LTR container.
        if (containsArabic(text)) {
            val fixed = wrapArabicLines(text)
            if (fixed === text || fixed == text) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        // Hebrew / other RTL: per-cue/line boundary-swap method (pre-processing skip needed)
        if (isSubRipSubtitle && isRtl(text)) {
            val fixed = fixHebrewLine(text, isSubRipSubtitle = true)
            if (fixed === text || fixed == text) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        return cue
    }

    /**
     * Applies [fixCueText] to every cue text. Returns the same list instance when nothing changes.
     * Intended for sidecar parse background threads, not the 60fps render ticker.
     */
    fun fixCuesTiming(
        cues: List<CuesWithTiming>,
        isSubRipSubtitle: Boolean = false
    ): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val earlyCues = entry.cues
            val modified = ArrayList<Cue>()
            for (i in earlyCues.indices) {
                val original = earlyCues[i]
                val fixed = fixCue(original, isSubRipSubtitle)
                if (fixed !== original) {
                    if (modified.isEmpty()) {
                        modified.addAll(earlyCues.subList(0, i))
                    }
                    modified.add(fixed)
                } else if (modified.isNotEmpty()) {
                    modified.add(original)
                }
            }
            if (modified.isNotEmpty()) {
                anyChanged = true
                out.add(copyCuesWithTiming(entry, modified))
            } else {
                out.add(entry)
            }
        }
        return if (anyChanged) out else cues
    }

    private fun copyCuesWithTiming(entry: CuesWithTiming, cues: List<Cue>): CuesWithTiming {
        val durationUs = when {
            entry.durationUs == C.TIME_UNSET && entry.durationUs == C.TIME_UNSET ->
                entry.endTimeUs.takeIf { it != C.TIME_UNSET }?.let { it - entry.startTimeUs } ?: C.TIME_UNSET
            else -> entry.durationUs
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    private fun wrapArabicLines(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 8)
        val lines = text.splitKeepDelimiters()
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i]
            if (line.isEmpty()) continue

            val hasTrailingR = line.endsWith("\r")
            val core = if (hasTrailingR) line.subSequence(0, line.length - 1) else line
            if (core.isEmpty()) {
                if (hasTrailingR) builder.append('\r')
                continue
            }

            if (containsArabic(core) && hasMessyTrailingBoundary(core)) {
                builder.append(applyVisualSwapping(core))
            } else {
                builder.append("\u200F\u202B").append(core).append("\u202C\u200F")
            }
            if (hasTrailingR) builder.append('\r')
        }
        return builder as CharSequence
    }

    private fun hasMessyTrailingBoundary(text: CharSequence): Boolean {
        var firstPunctIdx = -1
        for (i in 0 until text.length) {
            val c = text[i]
            if (isArabicBoundaryPunctuation(c)) {
                firstPunctIdx = i
                break
            }
        }
        if (firstPunctIdx == -1) return false

        var trailingPunctCount = 0
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            if (isArabicBoundaryPunctuation(c)) {
                trailingPunctCount++
            } else if (c != ' ') {
                break
            }
        }

        if (trailingPunctCount == 1 && firstPunctIdx >= text.length - 2) {
            val lastChar = text.trimEnd().lastOrNull()
            if (lastChar == '؟' || lastChar == '?' || lastChar == '.' || lastChar == '!' || lastChar == '،' || lastChar == ',') {
                return false
            }
        }

        var hasLetterOrDigitAfter = false
        for (i in (firstPunctIdx + 1) until text.length) {
            val c = text[i]
            if (Character.isLetterOrDigit(c)) {
                hasLetterOrDigitAfter = true
                break
            }
        }
        return hasLetterOrDigitAfter
    }

    private fun applyVisualSwapping(text: CharSequence): CharSequence {
        var rawString = text.toString()
        var hasQMark = false
        if (rawString.contains('؟') || rawString.contains('?')) {
            hasQMark = true
            rawString = rawString.replace("؟", "").replace("?", "")
        }

        val leading = StringBuilder()
        var start = 0
        while (start < rawString.length && isArabicBoundaryPunctuation(rawString[start])) {
            leading.append(mirrorChar(rawString[start]))
            start++
        }

        val trailing = StringBuilder()
        var end = rawString.length
        while (end > start && isArabicBoundaryPunctuation(rawString[end - 1])) {
            trailing.insert(0, mirrorChar(rawString[end - 1]))
            end--
        }

        val middle = rawString.subSequence(start, end)
        val result = StringBuilder()
        result.append("\u200F")
        result.append(trailing)
        result.append(middle)
        result.append(leading)
        result.append("\u200F")

        if (hasQMark) {
            val targetIdx = if (result.endsWith("\r")) result.length - 1 else result.length
            result.insert(targetIdx, "؟")
        }

        return result.toString()
    }

    private fun mirrorChar(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '<' -> '>'
        '>' -> '<'
        '«' -> '»'
        '»' -> '«'
        else -> c
    }

    private fun isArabicBoundaryPunctuation(c: Char): Boolean = when (c) {
        '.', ',', '!', '?', ';', ':', '،', '؛', '؟',
        '-', '–', '—', '…',
        '(', ')', '[', ']', '{', '}', '<', '>', '«', '»',
        '"', '\'', '“', '”', '‘', '’' -> true
        else -> false
    }

    private fun fixHebrewLine(line: CharSequence, isSubRipSubtitle: Boolean): CharSequence {
        if (!isSubRipSubtitle || line.isEmpty()) return line
        val preserveSpans = line is Spanned
        val text = line.toString()
        val fixed = buildString(text.length + 8) {
            val parts = text.split('\n')
            for (idx in parts.indices) {
                if (idx > 0) append('\n')
                append(swapBoundaryPunctuation(parts[idx]))
            }
        }
        if (fixed == text) return line
        if (preserveSpans) {
            val builder = SpannableStringBuilder(fixed)
            copySpans(line as Spanned, builder, text, fixed)
            return builder
        }
        return fixed
    }

    private fun swapBoundaryPunctuation(text: String): String {
        if (text.isEmpty()) return text
        var start = 0
        while (start < text.length && isPunctuation(text[start])) start++
        var end = text.length
        while (end > start && isPunctuation(text[end - 1])) end--

        val leading = text.substring(0, start)
        val middle = text.substring(start, end)
        val trailing = text.substring(end)

        if (leading.isEmpty() && trailing.isEmpty()) return text
        return trailing + middle + leading
    }

    private fun isPunctuation(c: Char): Boolean = when (c) {
        '.', ',', '!', '?', ';', ':', '-', '–', '—', '…',
        '(', ')', '[', ']', '{', '}', '<', '>',
        '"', '\'', '“', '”', '‘', '’' -> true
        else -> false
    }

    private fun hasAnyRtlCharacter(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            val d = Character.getDirectionality(text[i])
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) return true
        }
        return false
    }

    private fun containsArabic(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            val ub = Character.UnicodeBlock.of(text[i])
            if (ub == Character.UnicodeBlock.ARABIC ||
                ub == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                ub == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B ||
                ub == Character.UnicodeBlock.ARABIC_SUPPLEMENT
            ) return true
        }
        return false
    }

    private fun isRtl(text: CharSequence): Boolean {
        for (i in 0 until text.length) {
            val d = Character.getDirectionality(text[i])
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT) return true
        }
        return false
    }

    private fun CharSequence.splitKeepDelimiters(): List<CharSequence> {
        val lines = ArrayList<CharSequence>()
        var start = 0
        for (i in 0 until length) {
            if (this[i] == '\n') {
                lines.add(subSequence(start, i))
                start = i + 1
            }
        }
        if (start <= length) {
            lines.add(subSequence(start, length))
        }
        return lines
    }

    private fun copySpans(src: Spanned, dst: SpannableStringBuilder, oldStr: String, newStr: String) {
        val spans = src.getSpans(0, src.length, Any::class.java)
        for (span in spans) {
            val start = src.getSpanStart(span)
            val end = src.getSpanEnd(span)
            val flags = src.getSpanFlags(span)
            val ratio = if (oldStr.isNotEmpty()) newStr.length.toFloat() / oldStr.length else 1f
            val newStart = (start * ratio).toInt().coerceIn(0, dst.length)
            val newEnd = (end * ratio).toInt().coerceIn(newStart, dst.length)
            dst.setSpan(span, newStart, newEnd, flags)
        }
    }
}
