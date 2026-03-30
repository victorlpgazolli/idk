import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import platform.posix.localtime
import platform.posix.time
import platform.posix.time_tVar

object CommandExecutor {
    private val charPool = ('a'..'z') + ('0'..'9')

    fun execute(command: String, state: AppState) {
        when (command) {
            "debug" -> handleDebug(state)
            else -> {}
        }
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

    private fun handleDebug(state: AppState) {
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
}
