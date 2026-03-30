import kotlin.concurrent.AtomicReference

enum class AppMode {
    DEFAULT,
    DEBUG_CLASS_FILTER
}

data class Command(val name: String, val description: String)

data class AppState(
    var inputBuffer: String = "",
    var cursorPosition: Int = 0,
    var suggestions: List<Command> = emptyList(),
    var selectedSuggestionIndex: Int = -1,
    var commandHistory: MutableList<String> = mutableListOf(),
    var ctrlCPressed: Boolean = false,
    var ctrlCTimestamp: Long = 0L,
    var running: Boolean = true,
    
    // Class Filter Mode additions
    var mode: AppMode = AppMode.DEFAULT,
    var lastInputTimestamp: Long = 0L,
    var lastSearchedParam: String = "",
    val sharedFetchedClasses: AtomicReference<List<String>?> = AtomicReference(null),
    val sharedRpcError: AtomicReference<String?> = AtomicReference(null),
    var displayedClasses: List<String> = emptyList(),
    var selectedClassIndex: Int = -1,
    var isFetchingClasses: Boolean = false,
    var rpcError: String? = null
)
