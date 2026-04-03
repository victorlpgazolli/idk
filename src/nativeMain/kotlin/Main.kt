import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun currentTimeMillis(): Long {
    memScoped {
        val tv = alloc<timeval>()
        gettimeofday(tv.ptr, null)
        return tv.tv_sec * 1000L + tv.tv_usec / 1000L
    }
}

fun initDebugClassFilter(state: AppState, scope: CoroutineScope) {
    state.mode = AppMode.DEBUG_CLASS_FILTER
    state.isFetchingClasses = true
    scope.launch {
        val ok = RpcClient.ping()
        if (!ok) {
            state.sharedRpcError.value = "Frida bridge is not running on 127.0.0.1:8080. Start bridge.py"
            state.isFetchingClasses = false
        } else {
            val (pkgResult, _) = RpcClient.getPackageName()
            if (pkgResult != null) {
                state.sharedAppPackageName.value = pkgResult
            }
            val (result, error) = RpcClient.listClasses("", 0, 200)
            state.sharedFetchedClasses.value = result ?: emptyList()
            state.sharedRpcError.value = error
            state.isFetchingClasses = false
        }
    }
}

fun onInputChanged(state: AppState, resetCtrlC: Boolean = true) {
    if (resetCtrlC) state.ctrlCPressed = false
    state.historyNavigationIndex = -1
    
    if (state.mode == AppMode.DEFAULT) {
        state.suggestions = CommandRegistry.search(state.inputBuffer)
        state.selectedSuggestionIndex = if (state.suggestions.isNotEmpty()) 0 else -1
    } else {
        state.lastInputTimestamp = currentTimeMillis()
    }
}

fun main(args: Array<String>) {
    CacheManager.ensureCacheDir()

    var initialMode = AppMode.DEFAULT
    var inspectTarget = ""
    if (args.contains("--mode") && args.indexOf("--mode") + 1 < args.size) {
        val modeIdx = args.indexOf("--mode")
        val modeStr = args[modeIdx + 1]
        if (modeStr == "debug_entrypoint") {
            initialMode = AppMode.DEBUG_ENTRYPOINT
        } else if (modeStr == "debug_class_filter") {
            initialMode = AppMode.DEBUG_CLASS_FILTER
        } else if (modeStr == "debug_inspect_class") {
            initialMode = AppMode.DEBUG_INSPECT_CLASS
            if (modeIdx + 2 < args.size) {
                inspectTarget = args[modeIdx + 2]
            }
        }
    }

    if (initialMode == AppMode.DEFAULT && !TmuxManager.checkTmux()) {
        println("${Ansi.RED}tmux is unaccessible by idk, verify exec permissions and if tmux is installed${Ansi.RESET}")
        return
    }

    val state = AppState(mode = initialMode)
    state.commandHistory = HistoryStore.load()
    val scope = CoroutineScope(Dispatchers.Default)

    if (initialMode == AppMode.DEBUG_CLASS_FILTER) {
        initDebugClassFilter(state, scope)
    } else if (initialMode == AppMode.DEBUG_INSPECT_CLASS) {
        state.inspectTargetClassName = inspectTarget
        state.isFetchingInspection = true
        scope.launch {
            val ok = RpcClient.ping()
            if (!ok) {
                state.sharedRpcError.value = "Frida bridge is not running on 127.0.0.1:8080. Start bridge.py"
                state.isFetchingInspection = false
            } else {
                val (result, error) = RpcClient.inspectClass(inspectTarget)
                state.sharedInspectResult.value = result
                state.sharedRpcError.value = error
                state.isFetchingInspection = false
            }
        }
    }

    Terminal.enableRawMode()
    Renderer.render(state)

    while (state.running) {
        if (state.ctrlCPressed) {
            val elapsed = currentTimeMillis() - state.ctrlCTimestamp
            if (elapsed > 1500) {
                state.ctrlCPressed = false
                Renderer.render(state)
            }
        }

        val key = InputHandler.readKey()

        when (key) {
            is KeyEvent.CtrlC -> {
                if (state.ctrlCPressed) {
                    state.running = false
                } else {
                    state.ctrlCPressed = true
                    state.ctrlCTimestamp = currentTimeMillis()
                    Renderer.render(state)
                }
            }

            is KeyEvent.Char -> {
                if (state.mode == AppMode.DEBUG_INSPECT_CLASS && (key.c == 'h' || key.c == 'H')) {
                    val rows = state.buildInspectRows()
                    if (rows.isNotEmpty() && state.selectedClassIndex in rows.indices) {
                        val row = rows[state.selectedClassIndex]
                        if (row is InspectRow.StaticAttributeRow || row is InspectRow.StaticMethodRow) {
                            val signature = if (row is InspectRow.StaticAttributeRow) row.attribute else (row as InspectRow.StaticMethodRow).method
                            val type = if (row is InspectRow.StaticAttributeRow) HookType.FIELD else HookType.METHOD
                            
                            val existing = state.activeHooks.find { it.className == state.inspectTargetClassName && it.memberSignature == signature }
                            if (existing != null) {
                                state.activeHooks.remove(existing)
                                scope.launch {
                                    RpcClient.toggleHook(state.inspectTargetClassName, signature, false)
                                }
                            } else {
                                val target = HookTarget(state.inspectTargetClassName, signature, type)
                                state.activeHooks.add(target)
                                scope.launch {
                                    RpcClient.toggleHook(state.inspectTargetClassName, signature, true)
                                }
                            }
                            Renderer.render(state)
                        }
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS && (key.c == 'R' || key.c == 'r')) {
                    val rows = state.buildInspectRows()
                    if (rows.isNotEmpty() && state.selectedClassIndex in rows.indices) {
                        val row = rows[state.selectedClassIndex]
                        if (row is InspectRow.InstanceRow || row is InspectRow.InstanceAttributeRow) {
                            var targetId: String
                            var targetClassName: String
                            
                            if (row is InspectRow.InstanceRow) {
                                targetId = row.instance.id
                                targetClassName = state.inspectTargetClassName
                            } else {
                                val attrRow = row as InspectRow.InstanceAttributeRow
                                if (attrRow.attribute.childId != null && attrRow.isExpanded) {
                                    targetId = attrRow.attribute.childId!!
                                    targetClassName = attrRow.attribute.childClassName ?: ""
                                } else {
                                    targetId = attrRow.instanceId
                                    targetClassName = state.inspectTargetClassName 
                                }
                            }
                            
                            state.inspectExpandedInstances[targetId] = null
                            Renderer.render(state)
                            scope.launch {
                                val (attrs, err) = RpcClient.inspectInstance(targetClassName, targetId)
                                if (err == null && attrs != null) {
                                    state.sharedInspectInstanceResult.value = Pair(targetId, attrs)
                                } else {
                                    state.sharedRpcError.value = err ?: "Unknown error"
                                }
                            }
                        }
                    }
                } else {
                    state.inputBuffer = state.inputBuffer.substring(0, state.cursorPosition) +
                            key.c +
                            state.inputBuffer.substring(state.cursorPosition)
                    state.cursorPosition++
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.Backspace -> {
                if (state.cursorPosition > 0) {
                    state.inputBuffer = state.inputBuffer.substring(0, state.cursorPosition - 1) +
                            state.inputBuffer.substring(state.cursorPosition)
                    state.cursorPosition--
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.OptionBackspace -> {
                if (state.cursorPosition > 0) {
                    var pos = state.cursorPosition - 1
                    while (pos >= 0 && !state.inputBuffer[pos].isLetterOrDigit()) pos--
                    while (pos >= 0 && state.inputBuffer[pos].isLetterOrDigit()) pos--
                    val newCursor = pos + 1
                    
                    state.inputBuffer = state.inputBuffer.substring(0, newCursor) + 
                                        state.inputBuffer.substring(state.cursorPosition)
                    state.cursorPosition = newCursor
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.OptionLeft -> {
                var pos = state.cursorPosition - 1
                if (pos >= 0) {
                    while (pos >= 0 && !state.inputBuffer[pos].isLetterOrDigit()) pos--
                    while (pos >= 0 && state.inputBuffer[pos].isLetterOrDigit()) pos--
                    state.cursorPosition = pos + 1
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.OptionRight -> {
                var pos = state.cursorPosition
                if (pos < state.inputBuffer.length) {
                    while (pos < state.inputBuffer.length && !state.inputBuffer[pos].isLetterOrDigit()) pos++
                    while (pos < state.inputBuffer.length && state.inputBuffer[pos].isLetterOrDigit()) pos++
                    state.cursorPosition = pos
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.CmdLeft, is KeyEvent.CtrlA -> {
                state.cursorPosition = 0
                onInputChanged(state)
                Renderer.render(state)
            }

            is KeyEvent.CmdRight, is KeyEvent.CtrlE -> {
                state.cursorPosition = state.inputBuffer.length
                onInputChanged(state)
                Renderer.render(state)
            }

            is KeyEvent.CmdBackspace -> {
                if (state.cursorPosition > 0) {
                    state.inputBuffer = state.inputBuffer.substring(state.cursorPosition)
                    state.cursorPosition = 0
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.ArrowDown -> {
                if (state.mode == AppMode.DEBUG_ENTRYPOINT) {
                    state.debugEntrypointIndex = (state.debugEntrypointIndex + 1).coerceAtMost(1)
                    Renderer.render(state)
                } else if (state.mode == AppMode.DEFAULT) {
                    if (state.suggestions.isNotEmpty()) {
                        state.selectedSuggestionIndex =
                            (state.selectedSuggestionIndex + 1).coerceAtMost(state.suggestions.size - 1)
                        Renderer.render(state)
                    } else if (state.historyNavigationIndex > -1) {
                        state.historyNavigationIndex--
                        if (state.historyNavigationIndex < 0) {
                            state.historyNavigationIndex = -1
                            state.inputBuffer = state.savedInputBeforeHistory
                        } else {
                            state.inputBuffer = state.commandHistory[state.commandHistory.size - 1 - state.historyNavigationIndex]
                        }
                        state.cursorPosition = state.inputBuffer.length
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex + 1).coerceAtMost(state.displayedClasses.size - 1)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val rows = state.buildInspectRows()
                    if (rows.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex + 1).coerceAtMost(rows.size - 1)
                        Renderer.render(state)
                    }
                }
            }

            is KeyEvent.ArrowUp -> {
                if (state.mode == AppMode.DEBUG_ENTRYPOINT) {
                    state.debugEntrypointIndex = (state.debugEntrypointIndex - 1).coerceAtLeast(0)
                    Renderer.render(state)
                } else if (state.mode == AppMode.DEFAULT) {
                    if (state.suggestions.isNotEmpty()) {
                        state.selectedSuggestionIndex =
                            (state.selectedSuggestionIndex - 1).coerceAtLeast(0)
                        Renderer.render(state)
                    } else if (state.commandHistory.isNotEmpty()) {
                        if (state.historyNavigationIndex == -1) {
                            state.savedInputBeforeHistory = state.inputBuffer
                        }
                        state.historyNavigationIndex = (state.historyNavigationIndex + 1).coerceAtMost(state.commandHistory.size - 1)
                        state.inputBuffer = state.commandHistory[state.commandHistory.size - 1 - state.historyNavigationIndex]
                        state.cursorPosition = state.inputBuffer.length
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex - 1).coerceAtLeast(0)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val rows = state.buildInspectRows()
                    if (rows.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex - 1).coerceAtLeast(0)
                        Renderer.render(state)
                    }
                }
            }

            is KeyEvent.Tab -> {
                if (state.mode == AppMode.DEFAULT && state.suggestions.isNotEmpty() && state.selectedSuggestionIndex >= 0) {
                    val selected = state.suggestions[state.selectedSuggestionIndex]
                    state.inputBuffer = selected.name
                    state.cursorPosition = state.inputBuffer.length
                    onInputChanged(state)
                    Renderer.render(state)
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER && state.displayedClasses.isNotEmpty() && state.selectedClassIndex >= 0) {
                    state.inputBuffer = state.displayedClasses[state.selectedClassIndex]
                    state.cursorPosition = state.inputBuffer.length
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.Enter -> {
                if (state.mode == AppMode.DEBUG_ENTRYPOINT) {
                    if (state.debugEntrypointIndex == 0) {
                        initDebugClassFilter(state, scope)
                    }
                } else if (state.mode == AppMode.DEFAULT) {
                    val commandToExecute = if (state.suggestions.isNotEmpty() && state.selectedSuggestionIndex != -1) {
                        state.suggestions[state.selectedSuggestionIndex].name
                    } else {
                        state.inputBuffer
                    }

                    if (commandToExecute != state.inputBuffer) {
                        state.inputBuffer = commandToExecute
                        state.cursorPosition = state.inputBuffer.length
                        onInputChanged(state)
                        Renderer.render(state)
                    } else if (commandToExecute.isNotBlank()) {
                        HistoryStore.append(commandToExecute)
                        state.commandHistory.add(commandToExecute)
                        
                        state.inputBuffer = ""
                        state.cursorPosition = 0
                        state.suggestions = emptyList()
                        state.selectedSuggestionIndex = -1
                        state.ctrlCPressed = false
                        state.historyNavigationIndex = -1
                        
                        Renderer.render(state)
                        CommandExecutor.execute(commandToExecute, state, scope)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty() && state.selectedClassIndex >= 0) {
                        val selectedClass = state.displayedClasses[state.selectedClassIndex]
                        TmuxManager.appendInspectWindow(selectedClass)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val rows = state.buildInspectRows()
                    if (rows.isNotEmpty() && state.selectedClassIndex in rows.indices) {
                        when (val row = rows[state.selectedClassIndex]) {
                            is InspectRow.SectionStaticRow -> {
                                state.inspectStaticExpanded = !state.inspectStaticExpanded
                                Renderer.render(state)
                            }
                            is InspectRow.SectionInstancesRow -> {
                                state.inspectInstancesExpanded = !state.inspectInstancesExpanded
                                if (state.inspectInstancesExpanded && state.inspectInstancesList == null) {
                                    state.isFetchingInstancesList = true
                                    Renderer.render(state)
                                    scope.launch {
                                        val (res, err) = RpcClient.listInstances(state.inspectTargetClassName)
                                        if (err == null && res != null) {
                                            state.sharedInstancesListResult.value = res
                                        } else {
                                            state.sharedRpcError.value = err ?: "Unknown error fetching instances"
                                            state.isFetchingInstancesList = false
                                        }
                                    }
                                } else {
                                    Renderer.render(state)
                                }
                            }
                            is InspectRow.InstanceRow -> {
                                val id = row.instance.id
                                if (state.inspectExpandedInstances.containsKey(id)) {
                                    state.inspectExpandedInstances.remove(id)
                                    Renderer.render(state)
                                } else {
                                    state.inspectExpandedInstances[id] = null
                                    Renderer.render(state)
                                    scope.launch {
                                        val (attrs, err) = RpcClient.inspectInstance(state.inspectTargetClassName, id)
                                        if (err == null && attrs != null) {
                                            state.sharedInspectInstanceResult.value = Pair(id, attrs)
                                        } else {
                                            state.sharedRpcError.value = err ?: "Unknown error"
                                        }
                                    }
                                }
                            }
                            is InspectRow.InstanceAttributeRow -> {
                                val childId = row.attribute.childId
                                val childClassName = row.attribute.childClassName
                                if (childId != null && childClassName != null) {
                                    if (state.inspectExpandedInstances.containsKey(childId)) {
                                        state.inspectExpandedInstances.remove(childId)
                                        Renderer.render(state)
                                    } else {
                                        state.inspectExpandedInstances[childId] = null
                                        Renderer.render(state)
                                        scope.launch {
                                            val (attrs, err) = RpcClient.inspectInstance(childClassName, childId)
                                            if (err == null && attrs != null) {
                                                state.sharedInspectInstanceResult.value = Pair(childId, attrs)
                                            } else {
                                                state.sharedRpcError.value = err ?: "Unknown error"
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            is KeyEvent.ArrowLeft -> {
                if (state.cursorPosition > 0) {
                    state.cursorPosition--
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.ArrowRight -> {
                if (state.cursorPosition < state.inputBuffer.length) {
                    state.cursorPosition++
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.Esc -> {
                if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty() && state.selectedClassIndex >= 0 && !state.isFetchingInstances) {
                        val selectedClass = state.displayedClasses[state.selectedClassIndex]
                        state.isFetchingInstances = true
                        Renderer.render(state)
                        scope.launch {
                            val (res, err) = RpcClient.countInstances(selectedClass)
                            state.sharedInstanceCountResult.value = if (res != null) Pair(selectedClass, res) else null
                            state.sharedInstanceCountError.value = err
                        }
                    }
                }
            }

            is KeyEvent.Delete -> {
                if (state.cursorPosition < state.inputBuffer.length) {
                    state.inputBuffer = state.inputBuffer.substring(0, state.cursorPosition) +
                            state.inputBuffer.substring(state.cursorPosition + 1)
                    onInputChanged(state)
                    Renderer.render(state)
                }
            }

            is KeyEvent.Timeout -> {
                var needsRender = false

                if (Terminal.resized) {
                    Terminal.resized = false
                    needsRender = true
                }

                if (state.ctrlCPressed) {
                    val elapsed = currentTimeMillis() - state.ctrlCTimestamp
                    if (elapsed > 1500) {
                        state.ctrlCPressed = false
                        needsRender = true
                    }
                }

                // Gadget install status polling
                if (state.gadgetInstallStatus == GadgetInstallStatus.PREPARING_ADB || 
                    state.gadgetInstallStatus == GadgetInstallStatus.DEPLOYING_GADGET ||
                    state.gadgetInstallStatus == GadgetInstallStatus.INJECTING_JDWP) {
                    state.gadgetSpinnerFrame++
                    needsRender = true

                    val gadgetUpdate = state.sharedGadgetResult.value
                    if (gadgetUpdate != null) {
                        state.sharedGadgetResult.value = null
                        state.gadgetInstallStatus = gadgetUpdate.first
                        state.gadgetErrorMessage = gadgetUpdate.second

                        if (gadgetUpdate.first == GadgetInstallStatus.SUCCESS) {
                            // Reset gadget state and proceed with tmux
                            state.gadgetInstallStatus = GadgetInstallStatus.IDLE
                            state.gadgetErrorMessage = null
                            Renderer.render(state)
                            CommandExecutor.proceedWithTmux(state)
                            needsRender = true
                        }
                    }
                }

                if ((state.mode == AppMode.DEBUG_CLASS_FILTER && state.isFetchingClasses) || state.isFetchingInstances || state.isFetchingInstancesList) {
                    state.gadgetSpinnerFrame++
                    needsRender = true
                }

                if (state.isFetchingInstances) {
                    val countResult = state.sharedInstanceCountResult.value
                    val countError = state.sharedInstanceCountError.value
                    
                    if (countResult != null) {
                        state.instanceCounts[countResult.first] = countResult.second
                        state.sharedInstanceCountResult.value = null
                        state.isFetchingInstances = false
                        needsRender = true
                    }
                    
                    if (countError != null) {
                        state.rpcError = countError
                        state.sharedInstanceCountError.value = null
                        state.isFetchingInstances = false
                        needsRender = true
                    }
                }

                if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (currentTimeMillis() - state.lastInputTimestamp > 500 && state.inputBuffer != state.lastSearchedParam) {
                        state.lastSearchedParam = state.inputBuffer
                        state.isFetchingClasses = true
                        needsRender = true
                        
                        scope.launch {
                            val (res, err) = RpcClient.listClasses(state.lastSearchedParam, 0, 200)
                            state.sharedFetchedClasses.value = res ?: emptyList()
                            state.sharedRpcError.value = err
                        }
                    }
                    
                    val fetched = state.sharedFetchedClasses.value
                    if (fetched != null) {
                        state.displayedClasses = CommandExecutor.sortClasses(fetched, state.appPackageName)
                        state.selectedClassIndex = if (state.displayedClasses.isNotEmpty()) 0 else -1
                        state.sharedFetchedClasses.value = null
                        state.isFetchingClasses = false
                        needsRender = true
                    }
                    
                    val err = state.sharedRpcError.value
                    if (err != null) {
                        state.rpcError = err
                        state.sharedRpcError.value = null
                        state.isFetchingClasses = false
                        needsRender = true
                    }

                    val updatedPkg = state.sharedAppPackageName.value
                    if (updatedPkg != null) {
                        state.appPackageName = updatedPkg
                        state.sharedAppPackageName.value = null

                        if (state.inputBuffer.isEmpty()) {
                            state.inputBuffer = updatedPkg
                            state.cursorPosition = updatedPkg.length
                            onInputChanged(state)
                        }

                        if (state.displayedClasses.isNotEmpty()) {
                            state.displayedClasses = CommandExecutor.sortClasses(state.displayedClasses, state.appPackageName)
                            needsRender = true
                        }
                    }
                }

                if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val prevInspectStatus = state.sharedInspectResult.value
                    if (prevInspectStatus != null) {
                        state.inspectAttributes = prevInspectStatus.attributes
                        state.inspectMethods = prevInspectStatus.methods
                        state.sharedInspectResult.value = null
                        needsRender = true
                    }
                    
                    val listInstancesResult = state.sharedInstancesListResult.value
                    if (listInstancesResult != null) {
                        state.inspectInstancesList = listInstancesResult.instances
                        state.inspectInstancesTotalCount = listInstancesResult.totalCount
                        state.isFetchingInstancesList = false
                        state.sharedInstancesListResult.value = null
                        needsRender = true
                    }
                    
                    val inspectInstanceResult = state.sharedInspectInstanceResult.value
                    if (inspectInstanceResult != null) {
                        state.inspectExpandedInstances[inspectInstanceResult.first] = inspectInstanceResult.second
                        state.sharedInspectInstanceResult.value = null
                        needsRender = true
                    }

                    val inspectInstanceError = state.sharedInspectInstanceError.value
                    if (inspectInstanceError != null) {
                        state.inspectExpandedInstancesError[inspectInstanceError.first] = inspectInstanceError.second
                        state.sharedInspectInstanceError.value = null
                        needsRender = true
                    }

                    val previousError = state.sharedRpcError.value
                    if (previousError != null) {
                        state.rpcError = previousError
                        state.sharedRpcError.value = null
                        state.isFetchingInstancesList = false
                        needsRender = true
                    }
                }

                if (needsRender) {
                    Renderer.render(state)
                }
            }

            is KeyEvent.Unknown -> {}
        }
    }

    Terminal.disableRawMode()
    HistoryStore.save(state.commandHistory)
    print(Ansi.SHOW_CURSOR)
    print(Ansi.CLEAR_SCREEN)
    print(Ansi.CURSOR_HOME)
    print(Ansi.RESET)
    Terminal.flush()
}
