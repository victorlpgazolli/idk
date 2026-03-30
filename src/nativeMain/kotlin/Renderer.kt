object Ansi {
    const val RESET = "\u001b[0m"
    const val WHITE = "\u001b[97m"
    const val DIM = "\u001b[90m"
    const val GREEN = "\u001b[92m"
    const val YELLOW = "\u001b[93m"
    const val RED = "\u001b[91m"
    const val CLEAR_SCREEN = "\u001b[2J"
    const val CURSOR_HOME = "\u001b[H"
    const val HIDE_CURSOR = "\u001b[?25l"
    const val SHOW_CURSOR = "\u001b[?25h"
    const val CLEAR_LINE = "\u001b[K"

    fun moveTo(row: Int, col: Int): String = "\u001b[${row};${col}H"
}

object Renderer {
    private const val K_PURPLE = "\u001b[38;2;127;82;255m"
    private const val K_VIOLET = "\u001b[38;2;155;81;224m"
    private const val K_MAGENTA = "\u001b[38;2;195;69;204m"
    private const val K_PINK = "\u001b[38;2;227;68;156m"

    private const val DIM_GRAY = "\u001b[90m"
    private const val WHITE = "\u001b[97m"
    private const val RESET = "\u001b[0m"

    private const val LOGO = """
${K_MAGENTA}      ▄▄▄▄▄  ${K_MAGENTA}▄▄▄▄▄▄▄▄▄    ${K_PINK}▄▄▄▄    ▄▄▄▄ $RESET
${K_MAGENTA}      █████  ${K_MAGENTA}██████████▄  ${K_PINK}█████ ▄████▀ $RESET
${K_VIOLET}      █████  ${K_VIOLET}█████  █████ ${K_MAGENTA}█████████▀   $RESET
${K_VIOLET}      █████  ${K_VIOLET}█████  █████ ${K_MAGENTA}█████▀████▄  $RESET
${K_PURPLE}      █████  ${K_PURPLE}██████████▀  ${K_VIOLET}█████  ▀████ $RESET
${K_PURPLE}      ▀▀▀▀▀  ▀▀▀▀▀▀▀▀▀    ▀▀▀▀▀   ▀▀▀▀ $RESET
        $DIM_GRAY╭──[ ${WHITE}Interactive Debug Kit$DIM_GRAY ]──╮$RESET
"""
    private const val INSTRUCTIONS_TEXT = """
    
    Type your command and press Enter to execute it.
    Try typing 'debug' to start a new debug session.
    Press Ctrl+C to exit.
"""

    fun render(state: AppState) {
        val (termWidth, _) = Terminal.getSize()
        val width = if (termWidth > 4) termWidth - 2 else 70
        val buf = StringBuilder()

        buf.append(Ansi.CLEAR_SCREEN)
        buf.append(Ansi.CURSOR_HOME)
        buf.append(Ansi.HIDE_CURSOR)

        renderLogo(buf)
        renderWelcome(buf)
        
        if (state.mode == AppMode.DEFAULT) {
            renderHistory(buf, state)
        }
        
        renderCtrlCWarning(buf, state)
        renderInputBox(buf, state, width)
        
        if (state.mode == AppMode.DEFAULT) {
            renderSuggestions(buf, state)
        } else {
            renderClassList(buf, state)
        }

        buf.append(Ansi.SHOW_CURSOR)

        print(buf.toString())
        Terminal.flush()
    }

    private fun renderLogo(buf: StringBuilder) {
        buf.append(Ansi.DIM)
        for (line in LOGO.trimIndent().lines()) {
            buf.append(" ")
            buf.append(line)
            buf.append("\n")
        }
        buf.append(Ansi.RESET)
        buf.append("\n")
    }

    private fun renderWelcome(buf: StringBuilder) {
        buf.append(" ")
        buf.append(Ansi.WHITE)
        buf.append(INSTRUCTIONS_TEXT)
        buf.append(Ansi.RESET)
        buf.append("\n\n")
    }

    private fun renderHistory(buf: StringBuilder, state: AppState) {
        for (cmd in state.commandHistory) {
            buf.append(Ansi.DIM)
            buf.append(" > ")
            buf.append(Ansi.RESET)
            buf.append(Ansi.WHITE)
            buf.append(cmd)
            buf.append(Ansi.RESET)
            buf.append("\n")
        }
    }

    private fun renderCtrlCWarning(buf: StringBuilder, state: AppState) {
        if (state.ctrlCPressed) {
            buf.append(Ansi.YELLOW)
            buf.append(" Press Ctrl+C again to exit.")
            buf.append(Ansi.RESET)
            buf.append("\n")
        }
    }

    private fun renderInputBox(buf: StringBuilder, state: AppState, width: Int) {
        val innerWidth = width - 2
        val topBorder = "╭" + "─".repeat(innerWidth) + "╮"
        val bottomBorder = "╰" + "─".repeat(innerWidth) + "╯"

        val placeholder = if (state.mode == AppMode.DEFAULT) "Type your command" else "Search classes..."

        val inputContent = if (state.inputBuffer.isEmpty()) {
            "${Ansi.WHITE} > ${Ansi.RESET}${Ansi.DIM}  $placeholder${Ansi.RESET}"
        } else {
            "${Ansi.WHITE} > ${Ansi.RESET}${Ansi.WHITE}${state.inputBuffer}${Ansi.RESET}"
        }

        val visibleInputLength = if (state.inputBuffer.isEmpty()) {
            " >   $placeholder".length
        } else {
            " > ${state.inputBuffer}".length
        }
        val padding = if (innerWidth > visibleInputLength) innerWidth - visibleInputLength else 0

        buf.append(Ansi.DIM)
        buf.append(" ")
        buf.append(topBorder)
        buf.append(Ansi.RESET)
        buf.append("\n")

        buf.append(Ansi.DIM)
        buf.append(" │")
        buf.append(Ansi.RESET)
        buf.append(inputContent)
        buf.append(" ".repeat(padding))
        buf.append(Ansi.DIM)
        buf.append("│")
        buf.append(Ansi.RESET)
        buf.append("\n")

        buf.append(Ansi.DIM)
        buf.append(" ")
        buf.append(bottomBorder)
        buf.append(Ansi.RESET)
        buf.append("\n")
    }

    private fun renderSuggestions(buf: StringBuilder, state: AppState) {
        if (state.suggestions.isEmpty()) return

        for ((index, cmd) in state.suggestions.withIndex()) {
            val isSelected = index == state.selectedSuggestionIndex
            val nameColor = if (isSelected) Ansi.GREEN else Ansi.DIM
            val descColor = Ansi.DIM

            buf.append("  ")
            buf.append(nameColor)
            buf.append(cmd.name.padEnd(17))
            buf.append(Ansi.RESET)
            buf.append(descColor)
            buf.append(cmd.description)
            buf.append(Ansi.RESET)
            buf.append("\n")
        }
    }

    private fun renderClassList(buf: StringBuilder, state: AppState) {
        if (state.rpcError != null) {
            buf.append(Ansi.RED).append("  Error: ${state.rpcError}").append(Ansi.RESET).append("\n")
            return
        }

        if (state.isFetchingClasses && state.displayedClasses.isEmpty()) {
            buf.append(Ansi.DIM).append("  Fetching classes...").append(Ansi.RESET).append("\n")
            return
        }

        if (state.displayedClasses.isEmpty() && state.inputBuffer.isNotEmpty() && !state.isFetchingClasses) {
            buf.append(Ansi.DIM).append("  No matching classes found.").append(Ansi.RESET).append("\n")
            return
        }

        val maxItems = 15
        var startIdx = 0
        if (state.selectedClassIndex > maxItems / 2) {
            startIdx = state.selectedClassIndex - maxItems / 2
        }
        val endIdx = minOf(state.displayedClasses.size, startIdx + maxItems)
        
        for (i in startIdx until endIdx) {
            val className = state.displayedClasses[i]
            val isSelected = i == state.selectedClassIndex
            val prefix = if (isSelected) "${Ansi.GREEN}  > " else "    "
            val suffix = Ansi.RESET
            
            val query = state.lastSearchedParam
            val formattedName = if (query.isNotEmpty() && className.contains(query, ignoreCase = true)) {
                val start = className.indexOf(query, ignoreCase = true)
                val end = start + query.length
                val p1 = className.substring(0, start)
                val match = className.substring(start, end)
                val p2 = className.substring(end)
                
                val baseColor = if (isSelected) Ansi.GREEN else Ansi.DIM
                "$baseColor$p1${Ansi.RED}$match$baseColor$p2"
            } else {
                val baseColor = if (isSelected) Ansi.GREEN else Ansi.DIM
                "$baseColor$className"
            }
            
            buf.append(prefix)
            buf.append(formattedName)
            buf.append(suffix)
            buf.append("\n")
        }
        
        if (state.displayedClasses.size > endIdx) {
             buf.append(Ansi.DIM)
             buf.append("    ... and ${state.displayedClasses.size - endIdx} more")
             buf.append(Ansi.RESET)
             buf.append("\n")
        }
    }
}
