import kotlin.concurrent.AtomicReference
import kotlinx.serialization.Serializable

enum class AppMode {
    DEFAULT,
    DEBUG_CLASS_FILTER,
    DEBUG_INSPECT_CLASS
}

enum class GadgetInstallStatus {
    IDLE,
    VALIDATING,
    RUNNING_CHECKS,
    SUCCESS,
    ERROR
}

data class Command(val name: String, val description: String)

@Serializable
data class ClassInspectionResult(
    val attributes: List<String>,
    val methods: List<String>
)

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
    var rpcError: String? = null,
    
    // Inspect Mode additions
    var inspectTargetClassName: String = "",
    val sharedInspectResult: AtomicReference<ClassInspectionResult?> = AtomicReference(null),
    var inspectAttributes: List<String> = emptyList(),
    var inspectMethods: List<String> = emptyList(),
    var isFetchingInspection: Boolean = false,

    // Gadget Install Status
    var gadgetInstallStatus: GadgetInstallStatus = GadgetInstallStatus.IDLE,
    var gadgetErrorMessage: String? = null,
    val sharedGadgetResult: AtomicReference<Pair<GadgetInstallStatus, String?>?> = AtomicReference(null),
    var gadgetSpinnerFrame: Int = 0
)
