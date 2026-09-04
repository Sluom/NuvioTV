package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import java.util.regex.Pattern

object PlayerSubtitleRtlFix {

    private val RTL_CHAR_PATTERN = Pattern.compile("[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]")

    fun isRtlText(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        return RTL_CHAR_PATTERN.matcher(text).find()
    }

    fun fixCueText(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty() || !isRtlText(text)) return text
        val textStr = text.toString()
        val lines = textStr.split("\n")
        val fixedLines = lines.map { line ->
            if (isRtlText(line)) "\u202B$line\u202C" else line
        }
        return fixedLines.joinToString("\n")
    }

    fun fixCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!isRtlText(text)) return cue
        val fixedText = fixCueText(text)
        return cue.buildUpon().setText(fixedText).build()
    }

    fun <T> fixTimedCues(cues: T): T {
        return cues
    }

    fun isBuiltInSubtitle(subtitle: Any?): Boolean {
        return false
    }
}
