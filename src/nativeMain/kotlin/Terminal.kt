import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGWINCH
import platform.posix.signal
import platform.posix.ECHO
import platform.posix.ICANON
import platform.posix.ICRNL
import platform.posix.ISIG
import platform.posix.IEXTEN
import platform.posix.IXON
import platform.posix.STDIN_FILENO
import platform.posix.TCSAFLUSH
import platform.posix.VMIN
import platform.posix.VTIME
import platform.posix.tcgetattr
import platform.posix.tcsetattr
import platform.posix.termios
import platform.posix.memcpy
import platform.posix.winsize
import platform.posix.ioctl
import platform.posix.fflush
import platform.posix.stdout

object Terminal {
    private val savedBytes = ByteArray(sizeOf<termios>().toInt())
    var resized: Boolean = false

    fun enableRawMode() {
        memScoped {
            val orig = alloc<termios>()
            tcgetattr(STDIN_FILENO, orig.ptr)

            savedBytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), orig.ptr, sizeOf<termios>().toULong())
            }

            val raw = alloc<termios>()
            memcpy(raw.ptr, orig.ptr, sizeOf<termios>().toULong())

            raw.c_iflag = raw.c_iflag and (ICRNL or IXON).toULong().inv()
            raw.c_lflag = raw.c_lflag and (ECHO or ICANON or ISIG or IEXTEN).toULong().inv()
            raw.c_cc[VMIN] = 0u
            raw.c_cc[VTIME] = 1u

            tcsetattr(STDIN_FILENO, TCSAFLUSH, raw.ptr)
        }

        signal(SIGWINCH, staticCFunction<Int, Unit> { _ ->
            Terminal.resized = true
        })
    }

    fun disableRawMode() {
        memScoped {
            val restore = alloc<termios>()
            savedBytes.usePinned { pinned ->
                memcpy(restore.ptr, pinned.addressOf(0), sizeOf<termios>().toULong())
            }
            tcsetattr(STDIN_FILENO, TCSAFLUSH, restore.ptr)
        }
    }

    fun getSize(): Pair<Int, Int> {
        memScoped {
            val ws = alloc<winsize>()
            ioctl(STDIN_FILENO, 0x40087468u, ws.ptr)
            val cols = ws.ws_col.toInt()
            val rows = ws.ws_row.toInt()
            return Pair(if (cols > 0) cols else 80, if (rows > 0) rows else 24)
        }
    }

    fun flush() {
        fflush(stdout)
    }
}
