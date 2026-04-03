import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import platform.posix.localtime
import platform.posix.time
import platform.posix.time_tVar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object CommandExecutor {
    private val charPool = ('a'..'z') + ('0'..'9')

    fun execute(command: String, state: AppState, scope: CoroutineScope) {
        val parts = command.split(" ")
        val baseCommand = parts[0]

        when (baseCommand) {
            "debug" -> handleDebug(state, scope)
            "exit" -> state.running = false
            else -> {}
        }
    }

    fun sortClasses(classes: List<String>, appPackage: String): List<String> {
        if (appPackage.isEmpty()) return classes.sorted()
        
        val segments = appPackage.split('.')
        val firstTwo = if (segments.size >= 2) segments.take(2).joinToString(".") else ""
        
        return classes.sortedWith(compareByDescending<String> { className ->
            when {
                className.startsWith("$appPackage.") || className == appPackage -> 3
                firstTwo.isNotEmpty() && (className.startsWith("$firstTwo.") || className == firstTwo) -> 2
                else -> 1
            }
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
        state.gadgetInstallStatus = GadgetInstallStatus.PREPARING_ADB
        state.gadgetErrorMessage = null
        state.gadgetSpinnerFrame = 0

        scope.launch {
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

