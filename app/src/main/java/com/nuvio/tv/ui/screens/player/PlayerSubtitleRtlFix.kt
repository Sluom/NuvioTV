package com.nuvio.tv.ui.screens.player

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

    // مطابقة التوقيع الذي يتوقعه ملف PlayerSidecarSubtitles
    fun fixTimedCues(cues: Any?): Any? {
        return cues
    }

    fun isBuiltInSubtitle(subtitle: Any?): Boolean {
        return false
    }
}
