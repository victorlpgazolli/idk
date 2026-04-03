object Ansi {
    const val RESET = "\u001b[0m"
    const val WHITE = "\u001b[97m"
    const val DIM = "\u001b[90m"
    const val GREEN = "\u001b[92m"
    const val YELLOW = "\u001b[93m"
    const val BLUE = "\u001b[94m"
    const val RED = "\u001b[91m"
    const val CLEAR_SCREEN = "\u001b[2J"
    const val CURSOR_HOME = "\u001b[H"
    const val HIDE_CURSOR = "\u001b[?25l"
    const val SHOW_CURSOR = "\u001b[?25h"
    const val SAVE_CURSOR = "\u001b7"
    const val RESTORE_CURSOR = "\u001b8"
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
    private const val LIGHT_GRAY = "\u001b[38;5;250m"
    private const val PROPERTY_NAME = "\u001b[38;5;252m"
    private const val TYPE_BOOLEAN = "\u001b[38;5;39m"
    private const val TYPE_NUMBER = "\u001b[38;5;114m"
    private const val TYPE_STRING = "\u001b[38;5;173m"
    private const val TYPE_OTHER = "\u001b[38;5;43m"
    private const val RESET = "\u001b[0m"
    
    // Java Syntax Highlighting
    private const val J_MODIFIER = "\u001b[38;5;208m" // Orange
    private const val J_TYPE = "\u001b[38;5;114m"     // Greenish/Cyan
    private const val J_PACKAGE = "\u001b[90m"        // Dim Gray
    private const val J_CLASS = "\u001b[97m"          // White
    private const val J_METHOD = "\u001b[38;5;220m"    // Yellow
    private const val J_NUMBER = "\u001b[38;5;173m"    // Orange/Brown
    
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
        val (termWidth, termHeight) = Terminal.getSize()
        val width = if (termWidth > 4) termWidth - 2 else 70
        val buf = StringBuilder()

        buf.append(Ansi.CLEAR_SCREEN)
        buf.append(Ansi.CURSOR_HOME)
        buf.append(Ansi.HIDE_CURSOR)

        renderLogo(buf)
        if (state.mode == AppMode.DEFAULT) {
            renderWelcome(buf)
            renderHistory(buf, state)
            renderCtrlCWarning(buf, state)
            renderInputBox(buf, state, width)
            renderSuggestions(buf, state)
            buf.append(Ansi.RESTORE_CURSOR)
        } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
            renderWelcome(buf)
            renderClassFetchStatus(buf, state)
            renderCtrlCWarning(buf, state)
            renderInputBox(buf, state, width)
            renderClassList(buf, state, termWidth, termHeight)
            buf.append(Ansi.RESTORE_CURSOR)
        } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
            renderCtrlCWarning(buf, state)
            renderInspectHeader(buf, state, width)
            buf.append("\n")
            renderInspectClassList(buf, state, termWidth, termHeight)
        } else if (state.mode == AppMode.DEBUG_ENTRYPOINT) {
            renderWelcome(buf)
            renderCtrlCWarning(buf, state)
            renderDebugEntrypoint(buf, state)
        }

        renderFooter(buf, state, termWidth, termHeight)

        buf.append(Ansi.SHOW_CURSOR)

        print(buf.toString())
        Terminal.flush()
    }

    private fun renderFooter(buf: StringBuilder, state: AppState, termWidth: Int, termHeight: Int) {
        val footerText = when (state.mode) {
            AppMode.DEFAULT -> " [↑/↓] History  [Tab] Autocomplete  [Enter] Execute  [Ctrl+C] Quit "
            AppMode.DEBUG_ENTRYPOINT -> " [↑/↓] Navigate  [Enter] Select  [Ctrl+C] Quit "
            AppMode.DEBUG_CLASS_FILTER -> " [↑/↓] Navigate  [Enter] Inspect  [Esc] Count Instances  [Ctrl+C] Quit "
            AppMode.DEBUG_INSPECT_CLASS -> " [↑/↓] Navigate  [Enter] Expand/Collapse  [H] Hook  [R] Refresh  [Esc] Back  [Ctrl+C] Quit "
        }
        val padding = maxOf(0, termWidth - footerText.length)
        
        buf.append(Ansi.moveTo(termHeight, 1))
        buf.append("\u001b[7m") // Reverse Video
        buf.append(footerText)
        buf.append(" ".repeat(padding))
        buf.append(Ansi.RESET)
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

    private fun renderDebugEntrypoint(buf: StringBuilder, state: AppState) {
        val options = listOf(
            "Search & inspect classes instances",
            "Hook methods & watch changes"
        )
        
        buf.append("  ${Ansi.WHITE}Select debug action:${Ansi.RESET}\n")
        
        for ((index, option) in options.withIndex()) {
            val isSelected = index == state.debugEntrypointIndex
            val prefix = ListRenderer.selectionPrefix(isSelected, "  ")
            val color = if (isSelected) Ansi.WHITE else Ansi.DIM
            buf.append(prefix).append(color).append(option).append(Ansi.RESET).append("\n")
        }
    }

    private fun renderHistory(buf: StringBuilder, state: AppState) {
        val lastDebugIndex = state.commandHistory.lastIndexOf("debug")
        for ((index, cmd) in state.commandHistory.withIndex()) {
            buf.append(Ansi.DIM)
            buf.append(" > ")
            buf.append(Ansi.RESET)
            buf.append(Ansi.WHITE)
            buf.append(cmd)
            buf.append(Ansi.RESET)
            buf.append("\n")

            // Show gadget install status only below the LAST "debug" command
            if (index == lastDebugIndex && state.gadgetInstallStatus != GadgetInstallStatus.IDLE && state.gadgetInstallStatus != GadgetInstallStatus.SUCCESS) {
                renderGadgetStatus(buf, state)
            }
        }
    }

    private fun renderGadgetStatus(buf: StringBuilder, state: AppState) {
        val status = state.gadgetInstallStatus
        val frame = ListRenderer.spinnerFrame(state.gadgetSpinnerFrame)

        fun step(title: String, activeState: GadgetInstallStatus, pastStates: List<GadgetInstallStatus>): String {
            return when {
                status in pastStates || status == GadgetInstallStatus.SUCCESS -> "   [${Ansi.GREEN}✓${Ansi.RESET}] ${Ansi.DIM}$title${Ansi.RESET}"
                status == activeState -> "   [${LIGHT_GRAY}$frame${Ansi.RESET}] ${Ansi.WHITE}$title${Ansi.RESET}"
                else -> "   [ ] ${Ansi.DIM}$title${Ansi.RESET}"
            }
        }

        if (status != GadgetInstallStatus.IDLE) {
            buf.append(step("Preparing adb environment", GadgetInstallStatus.PREPARING_ADB, listOf(GadgetInstallStatus.DEPLOYING_GADGET, GadgetInstallStatus.INJECTING_JDWP))).append("\n")
            buf.append(step("Deploying frida-gadget.so", GadgetInstallStatus.DEPLOYING_GADGET, listOf(GadgetInstallStatus.INJECTING_JDWP))).append("\n")
            buf.append(step("Injecting via JDWP...", GadgetInstallStatus.INJECTING_JDWP, emptyList())).append("\n")
        }

        if (status == GadgetInstallStatus.ERROR) {
            val errorMsg = state.gadgetErrorMessage ?: "Unknown error"
            buf.append("   ${Ansi.RED}Caught an error: $errorMsg${Ansi.RESET}\n")
        }
    }

    private fun renderClassFetchStatus(buf: StringBuilder, state: AppState) {
        if (state.isFetchingClasses) {
            val frame = ListRenderer.spinnerFrame(state.gadgetSpinnerFrame)
            val suffix = if (state.inputBuffer.length < 2) " (this could take a while)" else ""
            buf.append("   $LIGHT_GRAY$frame Fetching available classes$suffix$RESET\n")
        } else if (state.isFetchingInstances) {
            val frame = ListRenderer.spinnerFrame(state.gadgetSpinnerFrame)
            buf.append("   $LIGHT_GRAY$frame Searching instances...$RESET\n")
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

        val visibleInputLength = if (state.inputBuffer.isEmpty()) {
            " >   $placeholder".length
        } else {
            " > ${state.inputBuffer}".length
        }
        
        val padding = maxOf(0, innerWidth - visibleInputLength)

        buf.append(Ansi.DIM)
        buf.append(" ")
        buf.append(topBorder)
        buf.append(Ansi.RESET)
        buf.append("\n")

        buf.append(Ansi.DIM)
        buf.append(" │")
        buf.append(Ansi.RESET)
        buf.append("${Ansi.WHITE} > ${Ansi.RESET}")
        
        if (state.inputBuffer.isEmpty()) {
            buf.append(Ansi.SAVE_CURSOR)
            buf.append("${Ansi.DIM}  $placeholder${Ansi.RESET}")
        } else {
            val textBeforeCursor = state.inputBuffer.substring(0, state.cursorPosition)
            val textAfterCursor = state.inputBuffer.substring(state.cursorPosition)
            buf.append("${Ansi.WHITE}$textBeforeCursor")
            buf.append(Ansi.SAVE_CURSOR)
            buf.append("$textAfterCursor${Ansi.RESET}")
        }

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
            val prefix = ListRenderer.selectionPrefix(isSelected, "  ")
            val nameColor = if (isSelected) Ansi.GREEN else Ansi.DIM
            val descColor = Ansi.DIM

            buf.append(prefix)
            buf.append(nameColor)
            buf.append(cmd.name.padEnd(17))
            buf.append(Ansi.RESET)
            buf.append(descColor)
            buf.append(cmd.description)
            buf.append(Ansi.RESET)
            buf.append("\n")
        }
    }

    private fun renderClassList(buf: StringBuilder, state: AppState, termWidth: Int, termHeight: Int) {
        if (state.rpcError != null) {
            buf.append(Ansi.RED).append("  Error: ${state.rpcError}").append(Ansi.RESET).append("\n")
            return
        }

        if (state.isFetchingClasses && state.displayedClasses.isEmpty()) {
            buf.append(Ansi.DIM).append("  Fetching classes...").append(Ansi.RESET).append("\n")
            return
        }

        if (state.displayedClasses.isEmpty() && state.inputBuffer.isNotEmpty() && !state.isFetchingClasses) {
            val emptyBox = """
                ${Ansi.DIM}╭─────────────────────────────╮${Ansi.RESET}
                ${Ansi.DIM}│${Ansi.RESET}      No matches found       ${Ansi.DIM}│${Ansi.RESET}
                ${Ansi.DIM}╰─────────────────────────────╯${Ansi.RESET}
            """.trimIndent()
            for (line in emptyBox.lines()) {
                buf.append("   ").append(line).append("\n")
            }
            return
        }

        val fixedLines = 20 // +1 for footer
        val maxItems = maxOf(3, termHeight - fixedLines - 1)
        val (startIdx, endIdx) = ListRenderer.computeViewport(state.displayedClasses.size, state.selectedClassIndex, maxItems)
        
        for (i in startIdx until endIdx) {
            val className = state.displayedClasses[i]
            val isSelected = i == state.selectedClassIndex
            val prefix = ListRenderer.selectionPrefix(isSelected, "  ")
            val suffix = Ansi.RESET
            
            val query = state.lastSearchedParam
            
            val lastDot = className.lastIndexOf('.')
            val packagePart = if (lastDot != -1) className.substring(0, lastDot + 1) else ""
            val namePart = if (lastDot != -1) className.substring(lastDot + 1) else className
            
            val packageBaseColor = if (isSelected) Ansi.GREEN else DIM_GRAY
            val nameBaseColor = WHITE
            
            fun formatPart(text: String, baseColor: String): String {
                if (query.isEmpty() || !text.contains(query, ignoreCase = true)) {
                    return "$baseColor$text"
                }
                val sb = StringBuilder()
                var currentPos = 0
                val lowerText = text.lowercase()
                val lowerQuery = query.lowercase()
                
                while (true) {
                    val start = lowerText.indexOf(lowerQuery, currentPos)
                    if (start == -1) {
                        sb.append(baseColor).append(text.substring(currentPos))
                        break
                    }
                    sb.append(baseColor).append(text.substring(currentPos, start))
                    sb.append(Ansi.YELLOW).append(text.substring(start, start + query.length))
                    currentPos = start + query.length
                }
                return sb.toString()
            }
            
            var formattedName = formatPart(packagePart, packageBaseColor) + formatPart(namePart, nameBaseColor)
            
            val count = state.instanceCounts[className]
            if (count != null) {
                formattedName += " ${Ansi.WHITE}[$count]${Ansi.RESET}"
            }
            
            buf.append(prefix)
            buf.append(formattedName)
            buf.append(suffix)
            buf.append("\n")
        }
        
        ListRenderer.renderScrollIndicator(buf, startIdx, endIdx, state.displayedClasses.size, termWidth)
    }

    private fun renderInspectHeader(buf: StringBuilder, state: AppState, width: Int) {
        val innerWidth = width - 2
        val topBorder = "╭" + "─".repeat(innerWidth) + "╮"
        val bottomBorder = "╰" + "─".repeat(innerWidth) + "╯"

        val maxTitleLen = maxOf(10, innerWidth - 15)
        val displayClassName = if (state.inspectTargetClassName.length > maxTitleLen) {
            "..." + state.inspectTargetClassName.takeLast(maxTitleLen - 3)
        } else {
            state.inspectTargetClassName
        }
        val title = " Inspecting: $displayClassName "
        val padding = maxOf(0, innerWidth - title.length)

        buf.append(Ansi.DIM).append(" ").append(topBorder).append(Ansi.RESET).append("\n")
        buf.append(Ansi.DIM).append(" │").append(Ansi.RESET)
        buf.append(K_VIOLET).append(title).append(Ansi.RESET)
        buf.append(" ".repeat(padding)).append(Ansi.DIM).append("│").append(Ansi.RESET).append("\n")
        
        val methodCount = state.activeHooks.count { it.type == HookType.METHOD }
        val fieldCount = state.activeHooks.count { it.type == HookType.FIELD }
        
        val methodLabel = if (methodCount == 1) "method" else "methods"
        val fieldLabel = if (fieldCount == 1) "field" else "fields"
        
        val hooksPrefix = " Hooks: [ "
        val methodStr = "$methodCount $methodLabel"
        val separator = ", "
        val fieldStr = "$fieldCount $fieldLabel"
        val hooksSuffix = " ] "
        
        val visibleHooksLen = hooksPrefix.length + methodStr.length + separator.length + fieldStr.length + hooksSuffix.length
        val hooksPadding = maxOf(0, innerWidth - visibleHooksLen)
        
        buf.append(Ansi.DIM).append(" │").append(Ansi.RESET)
        buf.append(Ansi.WHITE).append(hooksPrefix).append(Ansi.RESET)
        buf.append(Ansi.YELLOW).append(methodStr).append(Ansi.RESET)
        buf.append(Ansi.WHITE).append(separator).append(Ansi.RESET)
        buf.append(Ansi.BLUE).append(fieldStr).append(Ansi.RESET)
        buf.append(Ansi.WHITE).append(hooksSuffix).append(Ansi.RESET)
        buf.append(" ".repeat(hooksPadding)).append(Ansi.DIM).append("│").append(Ansi.RESET).append("\n")

        buf.append(Ansi.DIM).append(" ").append(bottomBorder).append(Ansi.RESET).append("\n")
    }

    private fun renderInspectClassList(buf: StringBuilder, state: AppState, termWidth: Int, termHeight: Int) {
        if (state.rpcError != null) {
            buf.append(Ansi.RED).append("  Error: ${state.rpcError}").append(Ansi.RESET).append("\n")
            return
        }

        if (state.isFetchingInspection && state.inspectAttributes.isEmpty() && state.inspectMethods.isEmpty()) {
            buf.append(Ansi.DIM).append("  Parsing class structure via Frida...").append(Ansi.RESET).append("\n")
            return
        }

        val rows = state.buildInspectRows()
        if (rows.isEmpty()) {
            val emptyBox = """
                ${Ansi.DIM}╭─────────────────────────────╮${Ansi.RESET}
                ${Ansi.DIM}│${Ansi.RESET}    No items to display      ${Ansi.DIM}│${Ansi.RESET}
                ${Ansi.DIM}╰─────────────────────────────╯${Ansi.RESET}
            """.trimIndent()
            for (line in emptyBox.lines()) {
                buf.append("   ").append(line).append("\n")
            }
            return
        }

        val fixedLines = 18 
        val maxItems = maxOf(3, termHeight - fixedLines)
        val (startIdx, endIdx) = ListRenderer.computeViewport(rows.size, state.selectedClassIndex, maxItems)

        for (i in startIdx until endIdx) {
            val row = rows[i]
            val isSelected = i == state.selectedClassIndex
            val selectionMarker = ListRenderer.selectionPrefix(isSelected, "    ")
            
            var visualIndent = 8
            val baseIndentStr = when (row) {
                is InspectRow.StaticAttributeRow, is InspectRow.StaticMethodRow -> {
                    visualIndent += 4
                    "    "
                }
                is InspectRow.InstanceRow -> {
                    visualIndent += 8
                    "        "
                }
                is InspectRow.InstanceAttributeRow -> {
                    val indentBuilder = StringBuilder("        ")
                    row.treeFlags.forEachIndexed { index, isLast ->
                        visualIndent += 4
                        if (index == row.treeFlags.lastIndex) {
                            indentBuilder.append(if (isLast) "└── " else "├── ")
                        } else {
                            indentBuilder.append(if (isLast) "    " else "│   ")
                        }
                    }
                    indentBuilder.toString()
                }
                is InspectRow.InfoRow -> {
                    val indentBuilder = StringBuilder("        ")
                    row.treeFlags.forEach { isLast ->
                        visualIndent += 4
                        indentBuilder.append(if (isLast) "    " else "│   ")
                    }
                    visualIndent += 4
                    indentBuilder.append("    ")
                    indentBuilder.toString()
                }
                else -> ""
            }
            val prefix = "$baseIndentStr$selectionMarker"
            
            when (row) {
                is InspectRow.SectionStaticRow -> {
                    val actionText = if (row.isExpanded) "Collapse static attributes and methods" else "Expand static attributes and methods"
                    buf.append(prefix).append(K_PURPLE).append("1. ").append(actionText).append(Ansi.RESET).append("\n")
                }
                is InspectRow.SectionInstancesRow -> {
                    val countInfo = if (state.inspectInstancesList != null) " (${state.inspectInstancesTotalCount})" else ""
                    val actionText = "Found instances$countInfo"
                    buf.append(prefix).append(K_PURPLE).append("2. ").append(actionText).append(Ansi.RESET).append("\n")
                }
                is InspectRow.StaticAttributeRow -> {
                    val isHooked = state.activeHooks.any { it.className == state.inspectTargetClassName && it.memberSignature == row.attribute }
                    val hookMarker = if (isHooked) "${Ansi.YELLOW}[H] ${Ansi.RESET}" else ""
                    buf.append(prefix).append("    ").append(hookMarker).append(highlightJavaSignature(row.attribute)).append(Ansi.RESET).append("\n")
                }
                is InspectRow.StaticMethodRow -> {
                    val isHooked = state.activeHooks.any { it.className == state.inspectTargetClassName && it.memberSignature == row.method }
                    val hookMarker = if (isHooked) "${Ansi.YELLOW}[H] ${Ansi.RESET}" else ""
                    buf.append(prefix).append("    ").append(hookMarker).append(highlightJavaSignature(row.method)).append(Ansi.RESET).append("\n")
                }
                is InspectRow.InstanceRow -> {
                    val treeLine = if (row.isLast) "└── " else "├── "
                    val summaryMax = maxOf(10, termWidth - visualIndent - 25)
                    val summary = if (row.instance.summary.length > summaryMax) {
                        row.instance.summary.take(summaryMax - 3) + "..."
                    } else {
                        row.instance.summary
                    }
                    buf.append(prefix).append(Ansi.DIM).append(treeLine).append(Ansi.RESET)
                        .append(PROPERTY_NAME).append("Instance (").append(TYPE_OTHER).append(row.instance.handle).append(PROPERTY_NAME).append(") ").append(DIM_GRAY).append(summary).append(Ansi.RESET).append("\n")
                }
                is InspectRow.InstanceAttributeRow -> {
                    val typeStr = "(${row.attribute.type})"
                    val typeColor = when (row.attribute.type.lowercase()) {
                        "boolean", "bool" -> TYPE_BOOLEAN
                        "int", "long", "float", "double", "short", "byte" -> TYPE_NUMBER
                        "string", "char", "charsequence" -> TYPE_STRING
                        else -> TYPE_OTHER
                    }
                    val valColor = if (row.attribute.value == "null") Ansi.RED else typeColor
                    
                    val valueMax = maxOf(10, termWidth - visualIndent - row.attribute.name.length - typeStr.length - 10)
                    val displayValue = if (row.attribute.value.length > valueMax) {
                        row.attribute.value.take(valueMax - 3) + "..."
                    } else {
                        row.attribute.value
                    }

                    buf.append(prefix)
                    buf.append(PROPERTY_NAME).append(row.attribute.name).append(Ansi.RESET)
                    buf.append(" ").append(typeColor).append(typeStr).append(Ansi.RESET)
                    buf.append(" : ")
                    if (row.attribute.type.lowercase() == "string" && row.attribute.value != "null") {
                        buf.append(valColor).append("\"").append(displayValue).append("\"").append(Ansi.RESET)
                    } else {
                        buf.append(valColor).append(displayValue).append(Ansi.RESET)
                    }
                    buf.append("\n")
                }
                is InspectRow.InfoRow -> {
                    val color = if (row.isError) Ansi.RED else if (row.isDim) Ansi.DIM else Ansi.RESET
                    val text = if (row.text.contains("loading", ignoreCase = true)) {
                        val frame = ListRenderer.spinnerFrame(state.gadgetSpinnerFrame)
                        "$frame ${row.text}"
                    } else {
                        row.text
                    }
                    buf.append(prefix).append(color).append(text).append(Ansi.RESET).append("\n")
                }
            }
        }

        ListRenderer.renderScrollIndicator(buf, startIdx, endIdx, rows.size, termWidth)
    }

    private fun highlightJavaSignature(signature: String): String {
        val modifiers = setOf("public", "private", "protected", "static", "final", "native", "synchronized", "transient", "volatile", "abstract")
        val primitives = setOf("int", "long", "boolean", "byte", "short", "float", "double", "void", "char")
        
        val sb = StringBuilder()
        val tokens = signature.split(" ", "(", ")", ",").filter { it.isNotEmpty() }
        
        // This is a simple tokenizer/highlighter. For a full TUI we might want something more robust.
        var currentIdx = 0
        val words = signature.split(Regex("(?<=[\\s(),])|(?=[\\s(),])"))
        
        for (word in words) {
            when {
                word.trim() in modifiers -> sb.append(J_MODIFIER).append(word)
                word.trim() in primitives -> sb.append(J_TYPE).append(word)
                word.trim().isEmpty() -> sb.append(word)
                word == "(" || word == ")" || word == "," -> sb.append(WHITE).append(word)
                else -> {
                    // Check if it's a qualified name
                    if (word.contains('.')) {
                        val lastDot = word.lastIndexOf('.')
                        val pkg = word.substring(0, lastDot + 1)
                        val name = word.substring(lastDot + 1)
                        sb.append(J_PACKAGE).append(pkg).append(J_CLASS).append(name)
                    } else {
                        // Probably a method name or something else
                        if (word.any { it.isDigit() }) {
                           sb.append(J_NUMBER).append(word)
                        } else {
                           sb.append(J_METHOD).append(word)
                        }
                    }
                }
            }
        }
        sb.append(RESET)
        return sb.toString()
    }
}
