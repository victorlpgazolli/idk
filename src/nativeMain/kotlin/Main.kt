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

fun main(args: Array<String>) {
    CacheManager.ensureCacheDir()

    var initialMode = AppMode.DEFAULT
    var inspectTarget = ""
    if (args.contains("--mode") && args.indexOf("--mode") + 1 < args.size) {
        val modeIdx = args.indexOf("--mode")
        val modeStr = args[modeIdx + 1]
        if (modeStr == "debug_class_filter") {
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
    val scope = CoroutineScope(Dispatchers.Default)

    if (initialMode == AppMode.DEBUG_CLASS_FILTER) {
        state.isFetchingClasses = true
        scope.launch {
            val ok = RpcClient.ping()
            if (!ok) {
                state.sharedRpcError.value = "Frida bridge is not running on 127.0.0.1:8080. Start bridge.py"
                state.isFetchingClasses = false
            } else {
                val (result, error) = RpcClient.listClasses("", 0, 200)
                state.sharedFetchedClasses.value = result ?: emptyList()
                state.sharedRpcError.value = error
                state.isFetchingClasses = false
            }
        }
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
                state.ctrlCPressed = false
                state.inputBuffer = state.inputBuffer.substring(0, state.cursorPosition) +
                        key.c +
                        state.inputBuffer.substring(state.cursorPosition)
                state.cursorPosition++
                
                if (state.mode == AppMode.DEFAULT) {
                    state.suggestions = CommandRegistry.search(state.inputBuffer)
                    state.selectedSuggestionIndex = if (state.suggestions.isNotEmpty()) 0 else -1
                } else {
                    state.lastInputTimestamp = currentTimeMillis()
                }
                Renderer.render(state)
            }

            is KeyEvent.Backspace -> {
                if (state.cursorPosition > 0) {
                    state.ctrlCPressed = false
                    state.inputBuffer = state.inputBuffer.substring(0, state.cursorPosition - 1) +
                            state.inputBuffer.substring(state.cursorPosition)
                    state.cursorPosition--
                    
                    if (state.mode == AppMode.DEFAULT) {
                        state.suggestions = CommandRegistry.search(state.inputBuffer)
                        state.selectedSuggestionIndex = if (state.suggestions.isNotEmpty()) 0 else -1
                    } else {
                        state.lastInputTimestamp = currentTimeMillis()
                    }
                    Renderer.render(state)
                }
            }

            is KeyEvent.ArrowDown -> {
                if (state.mode == AppMode.DEFAULT) {
                    if (state.suggestions.isNotEmpty()) {
                        state.selectedSuggestionIndex =
                            (state.selectedSuggestionIndex + 1).coerceAtMost(state.suggestions.size - 1)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex + 1).coerceAtMost(state.displayedClasses.size - 1)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    // Inspect scrolling logic offset simulation
                    val totalElements = state.inspectAttributes.size + state.inspectMethods.size
                    if (totalElements > 0) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex + 1).coerceAtMost(totalElements - 1)
                        Renderer.render(state)
                    }
                }
            }

            is KeyEvent.ArrowUp -> {
                if (state.mode == AppMode.DEFAULT) {
                    if (state.suggestions.isNotEmpty()) {
                        state.selectedSuggestionIndex =
                            (state.selectedSuggestionIndex - 1).coerceAtLeast(0)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty()) {
                        state.selectedClassIndex =
                            (state.selectedClassIndex - 1).coerceAtLeast(0)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val totalElements = state.inspectAttributes.size + state.inspectMethods.size
                    if (totalElements > 0) {
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
                    state.suggestions = CommandRegistry.search(state.inputBuffer)
                    state.selectedSuggestionIndex = if (state.suggestions.isNotEmpty()) 0 else -1
                    Renderer.render(state)
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER && state.displayedClasses.isNotEmpty() && state.selectedClassIndex >= 0) {
                    state.inputBuffer = state.displayedClasses[state.selectedClassIndex]
                    state.cursorPosition = state.inputBuffer.length
                    state.lastInputTimestamp = currentTimeMillis()
                    Renderer.render(state)
                }
            }

            is KeyEvent.Enter -> {
                if (state.mode == AppMode.DEFAULT) {
                    if (state.suggestions.isNotEmpty() && state.selectedSuggestionIndex >= 0) {
                        val selected = state.suggestions[state.selectedSuggestionIndex]
                        if (state.inputBuffer == selected.name) {
                            val cmd = state.inputBuffer
                            state.commandHistory.add(cmd)
                            state.inputBuffer = ""
                            state.cursorPosition = 0
                            state.suggestions = emptyList()
                            state.selectedSuggestionIndex = -1
                            state.ctrlCPressed = false
                            Renderer.render(state)
                            CommandExecutor.execute(cmd, state)
                            Renderer.render(state)
                        } else {
                            state.inputBuffer = selected.name
                            state.cursorPosition = state.inputBuffer.length
                            state.suggestions = CommandRegistry.search(state.inputBuffer)
                            state.selectedSuggestionIndex = if (state.suggestions.isNotEmpty()) 0 else -1
                            Renderer.render(state)
                        }
                    } else if (state.inputBuffer.isNotEmpty()) {
                        val cmd = state.inputBuffer
                        state.commandHistory.add(cmd)
                        state.inputBuffer = ""
                        state.cursorPosition = 0
                        state.suggestions = emptyList()
                        state.selectedSuggestionIndex = -1
                        state.ctrlCPressed = false
                        Renderer.render(state)
                        CommandExecutor.execute(cmd, state)
                        Renderer.render(state)
                    }
                } else if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    if (state.displayedClasses.isNotEmpty() && state.selectedClassIndex >= 0) {
                        val selectedClass = state.displayedClasses[state.selectedClassIndex]
                        TmuxManager.appendInspectWindow(selectedClass)
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    // Do nothing on enter
                }
            }

            is KeyEvent.ArrowLeft -> {
                if (state.cursorPosition > 0) state.cursorPosition--
            }

            is KeyEvent.ArrowRight -> {
                if (state.cursorPosition < state.inputBuffer.length) state.cursorPosition++
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
                        state.displayedClasses = fetched
                        state.selectedClassIndex = if (fetched.isNotEmpty()) 0 else -1
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
                }

                if (state.mode == AppMode.DEBUG_CLASS_FILTER) {
                    val previouslyFetched = state.sharedFetchedClasses.value
                    val previousError = state.sharedRpcError.value
                    if (previouslyFetched != null) {
                        state.displayedClasses = previouslyFetched
                        state.sharedFetchedClasses.value = null
                        if (state.selectedClassIndex >= state.displayedClasses.size) {
                            state.selectedClassIndex = if (state.displayedClasses.isNotEmpty()) 0 else -1
                        } else if (state.selectedClassIndex < 0 && state.displayedClasses.isNotEmpty()) {
                            state.selectedClassIndex = 0
                        }
                        needsRender = true
                    }
                    if (previousError != null) {
                        state.rpcError = previousError
                        state.sharedRpcError.value = null
                        needsRender = true
                    }
                } else if (state.mode == AppMode.DEBUG_INSPECT_CLASS) {
                    val prevInspectStatus = state.sharedInspectResult.value
                    val previousError = state.sharedRpcError.value
                    if (prevInspectStatus != null) {
                        state.inspectAttributes = prevInspectStatus.attributes
                        state.inspectMethods = prevInspectStatus.methods
                        state.sharedInspectResult.value = null
                        needsRender = true
                    }
                    if (previousError != null) {
                        state.rpcError = previousError
                        state.sharedRpcError.value = null
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
    print(Ansi.SHOW_CURSOR)
    print(Ansi.CLEAR_SCREEN)
    print(Ansi.CURSOR_HOME)
    print(Ansi.RESET)
    Terminal.flush()
}
