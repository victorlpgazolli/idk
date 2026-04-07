internal val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

object ListRenderer {
    fun computeViewport(totalItems: Int, selectedIndex: Int, maxVisible: Int): Pair<Int, Int> {
        if (totalItems <= maxVisible) {
            return 0 to totalItems
        }
        val start = (selectedIndex - maxVisible / 2).coerceIn(0, totalItems - maxVisible)
        val end = (start + maxVisible).coerceAtMost(totalItems)
        return start to end
    }

    fun selectionPrefix(isSelected: Boolean, indent: String = ""): String {
        return if (isSelected) "$indent\u001b[38;5;75m› ${Ansi.RESET}" else "$indent  "
    }

    fun spinnerFrame(frameCount: Int): String {
        return SPINNER_FRAMES[frameCount % SPINNER_FRAMES.size]
    }

    fun renderScrollIndicator(buf: StringBuilder, startIdx: Int, endIdx: Int, totalItems: Int, width: Int) {
        if (totalItems == 0) return
        val indicator = "[ ${startIdx + 1}-$endIdx / $totalItems ]"
        val padding = maxOf(0, width - indicator.length - 2)
        buf.append(" ".repeat(padding)).append(Ansi.DIM).append(indicator).append(Ansi.RESET).append("\n")
    }
}