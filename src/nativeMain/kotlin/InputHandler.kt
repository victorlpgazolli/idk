import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.STDIN_FILENO
import platform.posix.read

sealed class KeyEvent {
    data class Char(val c: kotlin.Char) : KeyEvent()
    data object Enter : KeyEvent()
    data object Tab : KeyEvent()
    data object Backspace : KeyEvent()
    data object ArrowUp : KeyEvent()
    data object ArrowDown : KeyEvent()
    data object ArrowLeft : KeyEvent()
    data object ArrowRight : KeyEvent()
    data object CtrlC : KeyEvent()
    data object Timeout : KeyEvent()
    data object Unknown : KeyEvent()
}

object InputHandler {
    private fun readByte(): Int {
        val buffer = ByteArray(1)
        val bytesRead = buffer.usePinned { pinned ->
            read(STDIN_FILENO, pinned.addressOf(0), 1u)
        }
        if (bytesRead <= 0) return -1
        return buffer[0].toInt() and 0xFF
    }

    fun readKey(): KeyEvent {
        val c = readByte()
        if (c == -1) return KeyEvent.Timeout

        return when (c) {
            3 -> KeyEvent.CtrlC
            9 -> KeyEvent.Tab
            13 -> KeyEvent.Enter
            127 -> KeyEvent.Backspace
            27 -> {
                val next = readByte()
                if (next == '['.code) {
                    when (readByte()) {
                        'A'.code -> KeyEvent.ArrowUp
                        'B'.code -> KeyEvent.ArrowDown
                        'C'.code -> KeyEvent.ArrowRight
                        'D'.code -> KeyEvent.ArrowLeft
                        else -> KeyEvent.Unknown
                    }
                } else {
                    KeyEvent.Unknown
                }
            }
            else -> {
                if (c in 32..126) {
                    KeyEvent.Char(c.toChar())
                } else {
                    KeyEvent.Unknown
                }
            }
        }
    }
}
