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

sealed class InspectRow {
    data class SectionStaticRow(val isExpanded: Boolean) : InspectRow()
    data class StaticAttributeRow(val attribute: String) : InspectRow()
    data class StaticMethodRow(val method: String) : InspectRow()
    data class SectionInstancesRow(val isExpanded: Boolean) : InspectRow()
    data class InstanceRow(val instance: InstanceInfo, val isExpanded: Boolean, val depth: Int = 0) : InspectRow()
    data class InstanceAttributeRow(val instanceId: String, val attribute: InstanceAttribute, val isExpanded: Boolean = false, val depth: Int = 0) : InspectRow()
}

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
    
    var instanceCounts: MutableMap<String, Int> = mutableMapOf(),
    var isFetchingInstances: Boolean = false,
    val sharedInstanceCountResult: AtomicReference<Pair<String, Int>?> = AtomicReference(null),
    val sharedInstanceCountError: AtomicReference<String?> = AtomicReference(null),
    
    // Inspect Mode additions
    var inspectTargetClassName: String = "",
    val sharedInspectResult: AtomicReference<ClassInspectionResult?> = AtomicReference(null),
    var inspectAttributes: List<String> = emptyList(),
    var inspectMethods: List<String> = emptyList(),
    var isFetchingInspection: Boolean = false,
    
    var inspectStaticExpanded: Boolean = false,
    var inspectInstancesExpanded: Boolean = false,
    var inspectInstancesList: List<InstanceInfo>? = null,
    var inspectInstancesTotalCount: Int = 0,
    var isFetchingInstancesList: Boolean = false,
    val sharedInstancesListResult: AtomicReference<ListInstancesResult?> = AtomicReference(null),
    var inspectExpandedInstances: MutableMap<String, List<InstanceAttribute>?> = mutableMapOf(),
    var inspectExpandedInstancesError: MutableMap<String, String> = mutableMapOf(),
    val sharedInspectInstanceResult: AtomicReference<Pair<String, List<InstanceAttribute>>?> = AtomicReference(null),
    val sharedInspectInstanceError: AtomicReference<Pair<String, String>?> = AtomicReference(null),


    // Gadget Install Status
    var gadgetInstallStatus: GadgetInstallStatus = GadgetInstallStatus.IDLE,
    var gadgetErrorMessage: String? = null,
    val sharedGadgetResult: AtomicReference<Pair<GadgetInstallStatus, String?>?> = AtomicReference(null),
    var gadgetSpinnerFrame: Int = 0
) {
    fun buildInspectRows(): List<InspectRow> {
        val rows = mutableListOf<InspectRow>()
        
        rows.add(InspectRow.SectionStaticRow(inspectStaticExpanded))
        if (inspectStaticExpanded) {
            inspectAttributes.forEach { rows.add(InspectRow.StaticAttributeRow(it)) }
            inspectMethods.forEach { rows.add(InspectRow.StaticMethodRow(it)) }
        }

        rows.add(InspectRow.SectionInstancesRow(inspectInstancesExpanded))
        if (inspectInstancesExpanded) {
            val list = inspectInstancesList
            if (list != null) {
                list.forEach { instance ->
                    val isExpanded = inspectExpandedInstances.containsKey(instance.id)
                    rows.add(InspectRow.InstanceRow(instance, isExpanded, 0))
                    if (isExpanded) {
                        appendAttributesRecursively(instance.id, 1, rows, setOf(instance.id))
                    }
                }
            }
        }
        return rows
    }

    private fun appendAttributesRecursively(nodeId: String, depth: Int, rows: MutableList<InspectRow>, visited: Set<String>) {
        val attrs = inspectExpandedInstances[nodeId]
        if (attrs != null) {
            attrs.forEach { attr ->
                val canExpand = attr.childId != null
                val isCycle = canExpand && attr.childId!! in visited
                val isExpanded = canExpand && !isCycle && inspectExpandedInstances.containsKey(attr.childId!!)
                
                // If it's a cycle but user expanded it, we might want to forcefully mark it as not expanded to prevent looping visual
                val actualExpanded = if (isCycle) false else isExpanded

                rows.add(InspectRow.InstanceAttributeRow(nodeId, attr, actualExpanded, depth))
                if (actualExpanded) {
                    appendAttributesRecursively(attr.childId!!, depth + 1, rows, visited + attr.childId!!)
                }
            }
        }
    }
}
