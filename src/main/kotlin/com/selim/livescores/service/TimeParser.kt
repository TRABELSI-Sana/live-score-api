package com.selim.livescores.service

object TimeParser {
    private val minuteRegex = Regex("""^(\d+)(?:\+(\d+))?$""")

    fun normalize(time: String?): String = (time ?: "")
        .trim()
        .replace("’", "'")
        .replace(" ", "")
        .removeSuffix("'")

    fun parseMinute(time: String?): Int? {
        val t = normalize(time)
        if (t.isEmpty()) return null
        val m = minuteRegex.matchEntire(t) ?: return null
        val base = m.groupValues[1].toIntOrNull() ?: return null
        val add = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        // Keep ordering stable for added-time (e.g., 45+2 => 4502)
        return base * 100 + add
    }

    fun parseMinute(minute: Int?, time: String?): Int? = minute ?: parseMinute(time)
}
