import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.fputs
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

object HookStore {
    private fun filePath(): String = "${CacheManager.cacheDir()}/hooks.json"

    fun load(): Set<HookTarget> {
        CacheManager.ensureCacheDir()
        val path = filePath()
        val file = fopen(path, "r") ?: return emptySet()
        
        val sb = StringBuilder()
        val buffer = ByteArray(4096)
        while (fgets(buffer.refTo(0), buffer.size, file) != null) {
            sb.append(buffer.toKString())
        }
        fclose(file)
        
        val content = sb.toString().trim()
        if (content.isEmpty()) return emptySet()
        
        return try {
            Json.decodeFromString<Set<HookTarget>>(content)
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun save(hooks: Set<HookTarget>) {
        CacheManager.ensureCacheDir()
        val path = filePath()
        val file = fopen(path, "w") ?: return
        
        val json = Json { prettyPrint = true }
        val content = json.encodeToString(hooks)
        fputs(content, file)
        fclose(file)
    }
}
