import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeoutOrNull

@Serializable
data class ListClassesParams(
    val search_param: String,
    val offset: Int,
    val limit: Int
)

@Serializable
data class JsonRpcRequestListClasses(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: ListClassesParams,
    val id: Int = 1
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String,
    val result: List<String>? = null,
    val error: JsonRpcError? = null,
    val id: Int? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String
)

object RpcClient {
    private val client = HttpClient(Darwin) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun ping(): Boolean {
        return try {
            val response: HttpResponse = withTimeoutOrNull(5000) {
                client.get("http://127.0.0.1:8080/ping")
            } ?: return false
            response.status.value in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun listClasses(searchParam: String, offset: Int = 0, limit: Int = 200): Pair<List<String>?, String?> {
        return try {
            val requestBody = JsonRpcRequestListClasses(
                method = "listClasses",
                params = ListClassesParams(searchParam, offset, limit)
            )

            val response: HttpResponse = withTimeoutOrNull(5000) {
                client.post("http://127.0.0.1:8080/rpc") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            } ?: return Pair(null, "RPC Timeout (5s)")

            if (response.status.value in 200..299) {
                val rpcResponse = response.body<JsonRpcResponse>()
                if (rpcResponse.error != null) {
                    Pair(null, rpcResponse.error.message)
                } else {
                    Pair(rpcResponse.result, null)
                }
            } else {
                Pair(null, "RPC HTTP Error: ${response.status.value}")
            }
        } catch (e: Exception) {
            Pair(null, "RPC Internal Error: ${e.message}")
        }
    }
}
