package app.xujing.data

import app.xujing.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiClient(private val secrets: SecretVault) {
    private val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).build()
    private val activeCalls=ConcurrentHashMap.newKeySet<Call>()
    fun cancel() { activeCalls.forEach { it.cancel() } }
    fun endpoint(base:String,path:String):String {
        val url=base.trim().toHttpUrlOrNull() ?: error("API URL 需要是有效的 http:// 或 https:// 地址")
        val current=url.encodedPath.trimEnd('/')
        val root=when {
            current.endsWith("/chat/completions") -> current.removeSuffix("/chat/completions")
            current.endsWith("/embeddings") -> current.removeSuffix("/embeddings")
            current.endsWith("/models") -> current.removeSuffix("/models")
            else -> current
        }
        return url.newBuilder().encodedPath("$root/$path").fragment(null).build().toString()
    }
    private fun request(connection:ApiConnection,url:String,body:JsonObject):Request {
        val builder=Request.Builder().url(url).header("Accept","application/json, text/event-stream").post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val key=secrets.get(connection.id)
        if(key.isNotBlank()) builder.header("Authorization","Bearer $key")
        connection.headers.forEach { (k,v) -> if(k.isNotBlank()) builder.header(k,v) }
        return builder.build()
    }
    private suspend fun <T> withResponse(request:Request, block:suspend (Response)->T):T {
        val call=client.newCall(request)
        activeCalls.add(call)
        try {
            val response=suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call:Call,response:Response) { if(continuation.isActive) continuation.resume(response) { response.close() } else response.close() }
                })
            }
            return response.use { block(it) }
        } finally { activeCalls.remove(call) }
    }
    suspend fun complete(connection:ApiConnection,messages:List<PromptMessage>,onDelta:suspend (String)->Unit):String = withContext(Dispatchers.IO) {
        val body=buildJsonObject {
            connection.parameters.forEach { (k,v) -> put(k,v) }
            put("model",connection.model)
            put("messages",JsonArray(messages.map { message -> buildJsonObject {
                put("role",message.role); put("content",message.content); message.name?.takeIf(String::isNotBlank)?.let { put("name",it) }
            } }))
            put("stream",connection.stream)
        }
        require(connection.model.isNotBlank()) { "请填写模型名称" }
        withResponse(request(connection,endpoint(connection.baseUrl,"chat/completions"),body)) { response ->
            if(!response.isSuccessful) throw IOException("API HTTP ${response.code}：${response.body?.string().orEmpty().take(1200)}")
            val source=response.body?.source() ?: throw IOException("API 返回了空响应")
            if(!connection.stream || response.header("Content-Type").orEmpty().contains("application/json")) {
                val json=AppJson.parseToJsonElement(source.readUtf8()).jsonObject
                val content=content(json.array("choices").firstOrNull()?.jsonObject?.obj("message")?.get("content"))
                require(content.isNotEmpty()) { "API 未返回可显示的消息内容" }; onDelta(content); return@withResponse content
            }
            val full=StringBuilder(); val event=StringBuilder(); var finished=false
            suspend fun consume():Boolean {
                val value=event.toString().trim(); event.setLength(0)
                if(value.isEmpty()) return false
                if(value=="[DONE]") { finished=true; return true }
                val json=AppJson.parseToJsonElement(value).jsonObject
                json["error"]?.let { throw IOException("API 错误：${it.toString().take(1000)}") }
                val choice=json.array("choices").firstOrNull()?.jsonObject ?: return false
                if(choice["finish_reason"]?.let { it !is JsonNull } == true) finished=true
                val delta=content(choice.obj("delta")["content"] ?: choice.obj("message")["content"] ?: choice["text"])
                if(delta.isNotEmpty()) { full.append(delta); onDelta(delta) }
                return false
            }
            while(!source.exhausted()) {
                coroutineContext.ensureActive()
                val line=source.readUtf8Line() ?: break
                if(line.isBlank()) { if(consume()) break }
                else if(line.startsWith("data:")) { if(event.isNotEmpty()) event.append('\n'); event.append(line.substring(5).trimStart()) }
            }
            if(event.isNotEmpty()) consume()
            if(!finished) throw IOException("API 流在完成前断开，已保留收到的部分回复")
            require(full.isNotEmpty()) { "API 流中未返回文本内容" }
            full.toString()
        }
    }
    suspend fun embed(connection:ApiConnection,model:String,texts:List<String>):List<FloatArray> = withContext(Dispatchers.IO) {
        val payload=buildJsonObject { put("model",model.ifBlank { connection.model }); put("input",JsonArray(texts.map(::JsonPrimitive))); put("encoding_format","float") }
        withResponse(request(connection,endpoint(connection.baseUrl,"embeddings"),payload)) { response ->
            if(!response.isSuccessful) throw IOException("Embeddings HTTP ${response.code}：${response.body?.string().orEmpty().take(800)}")
            val data=AppJson.parseToJsonElement(response.body?.string() ?: error("向量响应为空")).jsonObject.array("data")
                .map { it.jsonObject }.sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: 0 }
            require(data.size==texts.size) { "向量响应数量与请求不一致" }
            data.map { item -> item.array("embedding").map { it.jsonPrimitive.float }.toFloatArray().also { require(it.isNotEmpty() && it.all(Float::isFinite)) { "向量响应无效" } } }
        }
    }
    private fun content(element:JsonElement?):String = when(element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("") { (it as? JsonObject)?.text("text").orEmpty() }
        else -> ""
    }
}
