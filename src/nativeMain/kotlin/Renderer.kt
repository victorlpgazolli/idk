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
    
    private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

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
            renderClassList(buf, state, termHeight)
            buf.append(Ansi.RESTORE_CURSOR)
        } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
            renderCtrlCWarning(buf, state)
            renderInspectHeader(buf, state, width)
            renderInspectClassList(buf, state, termHeight)
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
        when (state.gadgetInstallStatus) {
            GadgetInstallStatus.VALIDATING -> {
                val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
                buf.append("   $LIGHT_GRAY$frame Validating debug status$RESET\n")
            }
            GadgetInstallStatus.RUNNING_CHECKS -> {
                val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
                buf.append("   $LIGHT_GRAY$frame Running checks to know if debugger is up and running$RESET\n")
            }
            GadgetInstallStatus.ERROR -> {
                val errorMsg = state.gadgetErrorMessage ?: "Unknown error"
                buf.append("   ${Ansi.RED}Caught an error: $errorMsg${Ansi.RESET}\n")
            }
            else -> {}
        }
    }

    private fun renderClassFetchStatus(buf: StringBuilder, state: AppState) {
        if (state.isFetchingClasses) {
            val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
            val suffix = if (state.inputBuffer.length < 2) " (this could take a while)" else ""
            buf.append("   $LIGHT_GRAY$frame Fetching available classes$suffix$RESET\n")
        } else if (state.isFetchingInstances) {
            val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
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

    private fun renderClassList(buf: StringBuilder, state: AppState, termHeight: Int) {
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

        val fixedLines = 19
        val maxItems = maxOf(3, termHeight - fixedLines - 1)
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
            var formattedName = if (query.isNotEmpty() && className.contains(query, ignoreCase = true)) {
                val start = className.indexOf(query, ignoreCase = true)
                val end = start + query.length
                val p1 = className.substring(0, start)
                val match = className.substring(start, end)
                val p2 = className.substring(end)
                
                val baseColor = if (isSelected) Ansi.GREEN else Ansi.DIM
                "$baseColor$p1${if (isSelected) Ansi.GREEN else Ansi.RED}$match$baseColor$p2"
            } else {
                val baseColor = if (isSelected) Ansi.GREEN else Ansi.DIM
                "$baseColor$className"
            }
            
            val count = state.instanceCounts[className]
            if (count != null) {
                formattedName += " ${Ansi.WHITE}[$count]${Ansi.RESET}"
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

    private fun renderInspectHeader(buf: StringBuilder, state: AppState, width: Int) {
        val innerWidth = width - 2
        val topBorder = "╭" + "─".repeat(innerWidth) + "╮"
        val bottomBorder = "╰" + "─".repeat(innerWidth) + "╯"

        val title = " Inspecting: ${state.inspectTargetClassName} "
        val padding = maxOf(0, innerWidth - title.length)

        buf.append(Ansi.DIM).append(" ").append(topBorder).append(Ansi.RESET).append("\n")
        buf.append(Ansi.DIM).append(" │").append(Ansi.RESET)
        buf.append(K_VIOLET).append(title).append(Ansi.RESET)
        buf.append(" ".repeat(padding)).append(Ansi.DIM).append("│").append(Ansi.RESET).append("\n")
        buf.append(Ansi.DIM).append(" ").append(bottomBorder).append(Ansi.RESET).append("\n\n")
    }

    private fun renderInspectClassList(buf: StringBuilder, state: AppState, termHeight: Int) {
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
            buf.append(Ansi.DIM).append("  No items to display.").append(Ansi.RESET).append("\n")
            return
        }

        val maxItems = if (termHeight > 15) termHeight - 15 else 20
        var startIdx = 0
        if (state.selectedClassIndex > maxItems / 2) {
            startIdx = state.selectedClassIndex - maxItems / 2
        }
        val endIdx = minOf(rows.size, startIdx + maxItems)

        for (i in startIdx until endIdx) {
            val row = rows[i]
            val isSelected = i == state.selectedClassIndex
            val selectionMarker = if (isSelected) "${Ansi.GREEN}   >  ${Ansi.RESET}" else "      "
            
            val baseIndentStr = when (row) {
                is InspectRow.StaticAttributeRow, is InspectRow.StaticMethodRow -> "    "
                is InspectRow.InstanceRow -> " ".repeat(row.depth * 8)
                is InspectRow.InstanceAttributeRow -> " ".repeat(row.depth * 8)
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
                    if (state.isFetchingInstancesList) {
                        val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
                        buf.append("      ").append("    $LIGHT_GRAY$frame Fetching instances...$RESET\n")
                    }
                }
                is InspectRow.StaticAttributeRow -> {
                    buf.append(prefix).append("    ").append(DIM_GRAY).append(row.attribute).append(Ansi.RESET).append("\n")
                }
                is InspectRow.StaticMethodRow -> {
                    buf.append(prefix).append("    ").append(DIM_GRAY).append(row.method).append(Ansi.RESET).append("\n")
                }
                is InspectRow.InstanceRow -> {
                    buf.append(prefix).append(PROPERTY_NAME).append("Instance (").append(TYPE_OTHER).append(row.instance.handle).append(PROPERTY_NAME).append(") ").append(DIM_GRAY).append(row.instance.summary).append(Ansi.RESET).append("\n")
                    if (row.isExpanded) {
                        val errorStr = state.inspectExpandedInstancesError[row.instance.id]
                        if (errorStr != null) {
                             buf.append(baseIndentStr).append("      ").append("        ${Ansi.RED}Error loading attributes: $errorStr${Ansi.RESET}\n")
                        } else {
                            val isFetchingAttrs = state.inspectExpandedInstances[row.instance.id] == null
                            if (isFetchingAttrs) {
                                val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
                                buf.append(baseIndentStr).append("      ").append("        $LIGHT_GRAY$frame Loading attributes...$RESET\n")
                            } else {
                                buf.append(baseIndentStr).append("      ").append("        ${DIM_GRAY}Attributes of this instance: (press ${Ansi.WHITE}R${DIM_GRAY} to refresh)${Ansi.RESET}\n")
                            }
                        }
                    }
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
                    
                    buf.append(prefix)
                    buf.append(PROPERTY_NAME).append(row.attribute.name).append(Ansi.RESET)
                    buf.append(" ").append(typeColor).append(typeStr).append(Ansi.RESET)
                    buf.append(" : ")
                    if (row.attribute.type.lowercase() == "string" && row.attribute.value != "null") {
                        buf.append(valColor).append("\"").append(row.attribute.value).append("\"").append(Ansi.RESET)
                    } else {
                        buf.append(valColor).append(row.attribute.value).append(Ansi.RESET)
                    }
                    buf.append("\n")

                    if (row.isExpanded && row.attribute.childId != null) {
                        val errorStr = state.inspectExpandedInstancesError[row.attribute.childId]
                        if (errorStr != null) {
                            buf.append(baseIndentStr).append("      ").append("        ${Ansi.RED}Error loading attributes: $errorStr${Ansi.RESET}\n")
                        } else {
                            val isFetchingAttrs = state.inspectExpandedInstances[row.attribute.childId] == null
                            if (isFetchingAttrs) {
                                val frame = SPINNER_FRAMES[state.gadgetSpinnerFrame % SPINNER_FRAMES.size]
                                buf.append(baseIndentStr).append("      ").append("        $LIGHT_GRAY$frame Loading attributes...$RESET\n")
                            } else {
                                buf.append(baseIndentStr).append("      ").append("        ${DIM_GRAY}Attributes of this instance: (press ${Ansi.WHITE}R${DIM_GRAY} to refresh)${Ansi.RESET}\n")
                            }
                        }
                    }
                }
            }
        }

        if (rows.size > endIdx) {
             val diff = rows.size - endIdx
             buf.append(Ansi.DIM).append("    ... and $diff more items").append(Ansi.RESET).append("\n")
        }
    }
}
