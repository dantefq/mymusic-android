package com.example.mymusic

private val timestamp = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

fun parseLrc(value: String): List<Pair<Long, String>> {
    val offset = offsetTag.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return value.lineSequence().flatMap { line ->
        val times = timestamp.findAll(line).toList()
        val text = line.substring(times.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
        times.asSequence().map { match ->
            val milliseconds = match.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
            ((match.groupValues[1].toLong() * 60 + match.groupValues[2].toLong()) * 1000 + milliseconds - offset) to text
        }
    }.sortedBy { it.first }.toList()
}

/** Positive lead advances lyrics. -1 preserves the instrumental intro before the first line. */
fun lyricIndex(lines: List<Pair<Long, String>>, position: Long, leadMs: Long = 0): Int {
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].first <= position + leadMs) { result = middle; low = middle + 1 }
        else high = middle - 1
    }
    return result
}
