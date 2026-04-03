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
    data object Esc : KeyEvent()
    data object Delete : KeyEvent()
    data object OptionBackspace : KeyEvent()
    data object OptionLeft : KeyEvent()
    data object OptionRight : KeyEvent()
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
            23 -> KeyEvent.OptionBackspace // Ctrl-W maps to deleting backward word natively
            127 -> KeyEvent.Backspace
            27 -> {
                val next = readByte()
                if (next == -1) {
                    KeyEvent.Esc
                } else if (next == '['.code) {
                    var b = readByte()
                    var seqStr = ""
                    while (b != -1 && b < 0x40) {
                        seqStr += b.toChar()
                        b = readByte()
                    }
                    when (b) {
                        'A'.code -> KeyEvent.ArrowUp
                        'B'.code -> KeyEvent.ArrowDown
                        'C'.code -> if (seqStr.contains("3")) KeyEvent.OptionRight else KeyEvent.ArrowRight
                        'D'.code -> if (seqStr.contains("3")) KeyEvent.OptionLeft else KeyEvent.ArrowLeft
                        '~'.code -> if (seqStr == "3" || seqStr.endsWith(";3")) KeyEvent.Delete else KeyEvent.Unknown
                        else -> KeyEvent.Unknown
                    }
                } else if (next == 127 || next == 8) {
                    KeyEvent.OptionBackspace
                } else if (next == 'b'.code) {
                    KeyEvent.OptionLeft
                } else if (next == 'f'.code) {
                    KeyEvent.OptionRight
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
