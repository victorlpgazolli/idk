import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import platform.posix.localtime
import platform.posix.time
import platform.posix.time_tVar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

object CommandExecutor {
    private val charPool = ('a'..'z') + ('0'..'9')

    fun execute(command: String, state: AppState, scope: CoroutineScope) {
        val parts = command.split(" ")
        val baseCommand = parts[0]

        when (baseCommand) {
            "debug" -> handleDebug(state, scope)
            "exit" -> state.running = false
            "clear" -> {
                state.commandHistory.clear()
                HistoryStore.clear()
            }
            else -> {}
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
                state.lastSearchedParam = state.inputBuffer
                val (result, error) = RpcClient.listClasses(state.inputBuffer, state.appPackageName, 0, 200)
                state.sharedFetchedClasses.value = result ?: emptyList()
                state.sharedRpcError.value = error
                state.isFetchingClasses = false
            }
        }
    }

    fun handleDebugEntrypoint(state: AppState, scope: CoroutineScope) {
        when (state.debugEntrypointIndex) {
            0 -> initDebugClassFilter(state, scope)
            1 -> {
                state.activeHooks.clear()
                state.activeHooks.addAll(HookStore.load(state.appPackageName))
                state.mode = AppMode.DEBUG_HOOK_WATCH
            }
        }
    }

    fun restartBridge(state: AppState, scope: CoroutineScope) {
        state.bridgeLogs = emptyList()
        val logFile = "${CacheManager.cacheDir()}/bridge.log"
        val pidFile = "${CacheManager.cacheDir()}/bridge.pid"
        val serialArg = if (state.adbSerial != null) " --serial ${state.adbSerial}" else ""
        platform.posix.system("python3 ./bridge/bridge.py$serialArg > \"$logFile\" 2>&1 & echo \$! > \"$pidFile\"")
    }

    fun sortClasses(classes: List<String>, appPackage: String, searchQuery: String = "", showSynthetic: Boolean = false): List<String> {
        val filteredClasses = if (!showSynthetic && !searchQuery.contains('$')) {
            classes.filter { !it.contains('$') }
        } else {
            classes
        }

        val segments = appPackage.split('.')
        val firstTwo = if (segments.size >= 2) segments.take(2).joinToString(".") else ""
        
        return filteredClasses.sortedWith(compareByDescending<String> { className ->
            var priority = 0
            
            if (appPackage.isNotEmpty()) {
                if (className.startsWith("$appPackage.") || className == appPackage) {
                    priority = 3
                } else if (firstTwo.isNotEmpty() && (className.startsWith("$firstTwo.") || className == firstTwo)) {
                    priority = 2
                } else {
                    priority = 1
                }
            } else {
                priority = 1
            }

            if (className.contains("[")) {
                priority = -1
            }

            if (searchQuery.isNotEmpty() && priority >= 0) {
                val simpleName = className.substringAfterLast('.')
                if (simpleName.startsWith(searchQuery, ignoreCase = true) || className.startsWith(searchQuery, ignoreCase = true)) {
                    priority += 10
                }
            }
            
            priority
        }.thenBy { it })
    }

    private fun generateSessionId(): String {
        return (1..5).map { charPool.random() }.joinToString("")
    }

    private fun formatTimestamp(): String {
        memScoped {
            val t = alloc<time_tVar>()
            time(t.ptr)
            val tm = localtime(t.ptr)?.pointed ?: return "00/00 00:00"

            val day = tm.tm_mday
            val month = tm.tm_mon + 1
            val hour = tm.tm_hour
            val minute = tm.tm_min

            val dayStr = if (day < 10) "0$day" else "$day"
            val monthStr = if (month < 10) "0$month" else "$month"
            val hourStr = if (hour < 10) "0$hour" else "$hour"
            val minuteStr = if (minute < 10) "0$minute" else "$minute"

            return "$dayStr/$monthStr $hourStr:$minuteStr"
        }
    }

    fun proceedWithTmux(state: AppState) {
        val sessionId = generateSessionId()

        if (!TmuxManager.createSession(sessionId)) {
            return
        }

        val timestamp = formatTimestamp()
        SessionStore.addSession(sessionId, timestamp)

        TmuxManager.attachSession(sessionId)

        if (!TmuxManager.sessionExists(sessionId)) {
            SessionStore.removeSession(sessionId)
        }
    }

    private fun handleDebug(state: AppState, scope: CoroutineScope) {
        state.gadgetInstallStatus = GadgetInstallStatus.WAITING_BRIDGE
        state.gadgetErrorMessage = null
        state.gadgetSpinnerFrame = 0

        scope.launch {
            state.sharedGadgetResult.value = Pair(GadgetInstallStatus.WAITING_BRIDGE, null)
            
            if (!RpcClient.ping()) {
                val logFile = "${CacheManager.cacheDir()}/bridge.log"
                val pidFile = "${CacheManager.cacheDir()}/bridge.pid"
                val serialArg = if (state.adbSerial != null) " --serial ${state.adbSerial}" else ""
                platform.posix.system("python3 ./bridge/bridge.py$serialArg > \"$logFile\" 2>&1 & echo \$! > \"$pidFile\"")
            }

            var bridgeReady = false
            for (i in 0..10) {
                if (RpcClient.ping()) {
                    bridgeReady = true
                    break
                }
                delay(500)
            }
            
            if (!bridgeReady) {
                state.sharedGadgetResult.value = Pair(GadgetInstallStatus.ERROR, "Bridge not ready. Is it running?")
                return@launch
            }

            state.sharedGadgetResult.value = Pair(GadgetInstallStatus.PREPARING_ADB, null)

            val (envResult, envError) = RpcClient.prepareEnvironment()
            if (envError != null || envResult == null) {
                state.sharedGadgetResult.value = Pair(GadgetInstallStatus.ERROR, envError ?: "Failed to prepare ADB environment")
                return@launch
            }

            state.sharedGadgetResult.value = Pair(GadgetInstallStatus.DEPLOYING_GADGET, null)
            val (pushStatus, pushError) = RpcClient.checkOrPushGadget()
            if (pushError != null) {
                state.sharedGadgetResult.value = Pair(GadgetInstallStatus.ERROR, pushError)
                return@launch
            }

            state.sharedGadgetResult.value = Pair(GadgetInstallStatus.INJECTING_JDWP, null)
            val (injectStatus, injectError) = RpcClient.injectJdwp(envResult.target, envResult.port, envResult.package_name)
            
            when (injectStatus) {
                "completed", "gadget_detected" -> {
                    state.sharedGadgetResult.value = Pair(GadgetInstallStatus.SUCCESS, null)
                }
                else -> {
                    state.sharedGadgetResult.value = Pair(GadgetInstallStatus.ERROR, injectError ?: "Unknown error during JDWP injection")
                }
            }
        }
    }
}

