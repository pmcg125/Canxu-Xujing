package app.xujing.data

import android.content.Context
import ai.onnxruntime.*
import app.xujing.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

class EmbeddingManager(private val context:Context,private val store:AppStore,private val api:ApiClient) {
    private val root=File(context.filesDir,"models").apply { mkdirs() }
    private val mutex=Mutex()
    private var loadedId:String?=null
    private var session:OrtSession?=null
    private var tokenizer:WordPieceTokenizer?=null
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    fun installed():List<LocalModelPackage> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".import-") }.mapNotNull { dir ->
        runCatching { AppJson.decodeFromString<LocalModelPackage>(File(dir,"model-manifest.json").readText()).copy(directory=dir.absolutePath) }.getOrNull()
    }
    suspend fun importPackage(input:InputStream):LocalModelPackage = withContext(Dispatchers.IO) {
        val staging=File(root,".import-${newId()}").apply { mkdirs() }
        try {
            var total=0L; var count=0
            ZipInputStream(input).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count <= 128) { "模型包文件过多" }
                    val file=File(staging,entry.name.replace('\\','/')).canonicalFile
                    require(file.toPath().startsWith(staging.canonicalFile.toPath())) { "模型包存在非法路径" }
                    if(entry.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { output -> val buffer=ByteArray(64*1024); while(true) { val n=zip.read(buffer); if(n<0) break; total+=n; require(total<=768L*1024*1024) { "模型包超过768 MiB，请使用轻量模型" }; output.write(buffer,0,n) } }
                    }
                    zip.closeEntry()
                }
            }
            val manifest=File(staging,"model-manifest.json").takeIf { it.isFile } ?: File(staging,"model.json")
            require(manifest.isFile) { "ZIP根目录需要 model-manifest.json" }
            val model=AppJson.decodeFromString<LocalModelPackage>(manifest.readText())
            require(model.id.matches(Regex("[A-Za-z0-9._-]{1,100}"))) { "模型ID只能包含字母、数字、点、横线和下划线" }
            fun checked(path:String):File = File(staging,path).canonicalFile.also {
                require(it.toPath().startsWith(staging.canonicalFile.toPath()) && it.isFile) { "模型包缺少文件：$path" }
            }
            val weights=checked(model.modelFile); val tokenizerFile=checked(model.tokenizerFile)
            require(weights.extension.lowercase()=="onnx") { "当前本地引擎支持ONNX模型" }
            require(model.pooling in setOf("cls","mean")) { "pooling 需要为 cls 或 mean" }
            require(model.maxTokens in 8..4096) { "模型输入长度配置不支持" }
            WordPieceTokenizer(tokenizerFile,model.maxTokens)
            val weightHash=shaFile(weights)
            model.sha256?.let { require(it.equals(weightHash,true)) { "模型权重SHA-256与清单不一致" } }
            val identity=sha((weightHash+shaFile(tokenizerFile)).toByteArray()).take(12)
            val finalId="${model.id.take(70)}-$identity"
            val finalDir=File(root,finalId)
            require(!finalDir.exists()) { "此模型版本已安装" }
            val saved=model.copy(id=finalId,directory=finalDir.absolutePath,sha256=weightHash)
            File(staging,"model-manifest.json").writeText(AppJson.encodeToString(saved))
            require(staging.renameTo(finalDir)) { "无法保存模型目录" }
            saved
        } finally { if(staging.exists()) staging.deleteRecursively() }
    }
    suspend fun delete(id:String) = withContext(Dispatchers.IO) { mutex.withLock {
        val model=installed().firstOrNull { it.id==id } ?: return@withLock
        if(loadedId==id) { session?.close(); session=null; tokenizer=null; loadedId=null }
        val dir=File(model.directory).canonicalFile
        require(dir.parentFile==root.canonicalFile) { "模型路径无效" }
        require(dir.deleteRecursively()) { "无法删除模型" }
    } }
    private fun profile(state:AppState):String {
        val e=state.embedding
        val spec=if(e.provider=="local") installed().firstOrNull { it.id==e.localModelId }?.let { AppJson.encodeToString(it) }
            ?: error("长期记忆尚未就绪：请先导入并选择本地模型")
        else state.connections.firstOrNull { it.id==e.connectionId }?.let { "${it.id}|${it.baseUrl}|${e.remoteModel.ifBlank { it.model }}|${it.parameters}|${it.headers.toSortedMap()}" }
            ?: error("请配置远程Embeddings连接")
        return sha("xujing-wordpiece-v1|${e.provider}|${e.chunkChars}|$spec".toByteArray())
    }
    suspend fun rebuild(state:AppState,chat:ChatSession,force:Boolean=false,onStatus:(String)->Unit = {}) = withContext(Dispatchers.IO) {
        val profile=profile(state)
        if(force) store.deleteProfileVectors(profile,chat.id)
        val chunks=chunks(chat,chunkSize(state))
        val existing=store.vectorRows(profile,chat.id).associateBy { it.id }
        val missing=chunks.filter { it.first !in existing }
        missing.chunked(if(state.embedding.provider=="remote") 16 else 1).forEachIndexed { batchIndex,batch ->
            coroutineContext.ensureActive()
            val vectors=embeddings(state,batch.map { it.second },false)
            batch.zip(vectors).forEach { (chunk,vector) -> store.putVector(profile,chat.id,VectorRow(chunk.first,chunk.second,vector)) }
            onStatus("正在建立记忆索引 ${(batchIndex+1)*if(state.embedding.provider=="remote") 16 else 1}/${missing.size}")
        }
        store.pruneVectors(profile,chat.id,chunks.map { it.first }.toSet())
    }
    suspend fun retrieve(state:AppState,chat:ChatSession):List<String> = withContext(Dispatchers.IO) {
        val profile=profile(state)
        val rows=store.vectorRows(profile,chat.id)
        if(rows.isEmpty()) return@withContext emptyList()
        val queryLimit=if(state.embedding.provider=="local") ((installed().firstOrNull { it.id==state.embedding.localModelId }?.maxTokens ?: 512)-32).coerceAtLeast(1) else 2400
        val query=chat.messages.takeLast(3).joinToString("\n") { it.content }.takeLast(queryLimit)
        if(query.isBlank()) return@withContext emptyList()
        val q=embeddings(state,listOf(query),true).single()
        val historyWindow=(chat.extra["maxHistoryMessages"] as? JsonPrimitive)?.intOrNull ?: 40
        val current=chat.messages.takeLast(historyWindow.coerceAtLeast(1)).map { it.id }.toSet()
        val valid=chunks(chat,chunkSize(state)).map { it.first }.toSet()
        rows.asSequence().filter { it.id in valid && it.id.substringBefore(':') !in current }.map { it to cosine(it.vector,q) }
            .filter { it.second >= state.embedding.threshold }.sortedByDescending { it.second }.take(state.embedding.topK.coerceIn(1,100))
            .map { it.first.text }.toList()
    }
    private fun chunkSize(state:AppState):Int = if(state.embedding.provider=="local") minOf(state.embedding.chunkChars,(installed().firstOrNull { it.id==state.embedding.localModelId }?.maxTokens ?: 512)-32).coerceAtLeast(32) else state.embedding.chunkChars
    private fun chunks(chat:ChatSession,size:Int):List<Pair<String,String>> = chat.messages.filter { it.content.isNotBlank() && it.role in setOf("user","assistant") }.flatMap { m ->
        m.content.chunked(size.coerceIn(32,16000)).mapIndexed { i,text -> "${m.id}:$i:${sha(text.toByteArray()).take(16)}" to "${m.name ?: m.role}：$text" }
    }
    private suspend fun embeddings(state:AppState,texts:List<String>,query:Boolean):List<FloatArray> {
        if(state.embedding.provider=="remote") {
            val c=state.connections.firstOrNull { it.id==state.embedding.connectionId } ?: error("Embeddings连接不存在")
            return api.embed(c,state.embedding.remoteModel,texts)
        }
        return mutex.withLock {
            val model=installed().firstOrNull { it.id==state.embedding.localModelId } ?: error("请先导入本地向量模型")
            if(loadedId!=model.id) {
                session?.close()
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    session=environment.createSession(File(model.directory,model.modelFile).absolutePath,options)
                }
                tokenizer=WordPieceTokenizer(File(model.directory,model.tokenizerFile),model.maxTokens)
                loadedId=model.id
            }
            texts.map { text ->
                val ids=tokenizer!!.encode((if(query) model.queryPrefix else model.passagePrefix)+text)
                val ort=session!!
                val tensors=mutableMapOf<String,OnnxTensor>()
                try {
                    for(name in ort.inputNames) {
                        val values=when(name) { "input_ids" -> ids; "attention_mask" -> LongArray(ids.size) { 1L }; "token_type_ids" -> LongArray(ids.size); else -> error("不支持的模型输入：$name") }
                        tensors[name]=OnnxTensor.createTensor(environment,arrayOf(values))
                    }
                    ort.run(tensors).use { result ->
                        val tensor=result[0] as? OnnxTensor ?: error("模型输出不是向量Tensor")
                        val shape=(tensor.info as TensorInfo).shape
                        val buffer=tensor.floatBuffer ?: error("模型输出需要为float类型")
                        val floats=FloatArray(buffer.remaining()); buffer.get(floats)
                        val vector=when(shape.size) {
                            2 -> floats.copyOfRange(0,shape.last().toInt())
                            3 -> {
                                val dims=shape.last().toInt(); val tokens=shape[1].toInt()
                                if(model.pooling=="cls") floats.copyOfRange(0,dims)
                                else FloatArray(dims) { d -> var sum=0f; for(t in 0 until tokens) sum+=floats[t*dims+d]; sum/tokens }
                            }
                            else -> error("不支持的模型输出维度：${shape.toList()}")
                        }
                        require(model.dimensions==0 || model.dimensions==vector.size) { "模型输出维度与清单不一致" }
                        if(model.normalize) normalize(vector) else vector
                    }
                } finally { tensors.values.forEach { it.close() } }
            }
        }
    }
    companion object {
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun shaFile(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun normalize(v:FloatArray):FloatArray { val norm=sqrt(v.sumOf { (it*it).toDouble() }); return if(norm>0) FloatArray(v.size) { (v[it]/norm).toFloat() } else v }
        fun cosine(a:FloatArray,b:FloatArray):Double { require(a.size==b.size) { "向量维度不匹配，请重建索引" }; var dot=0.0; var aa=0.0; var bb=0.0; for(i in a.indices) { dot+=a[i]*b[i]; aa+=a[i]*a[i]; bb+=b[i]*b[i] }; return if(aa>0&&bb>0) dot/sqrt(aa*bb) else 0.0 }
    }
}

/** BERT WordPiece tokenizer for explicitly supported Chinese small-model packages. */
private class WordPieceTokenizer(file:File,private val maxTokens:Int) {
    private val vocab:Map<String,Int>
    private val lowercase:Boolean
    init {
        if(file.extension=="json") {
            val json=AppJson.parseToJsonElement(file.readText()).jsonObject
            val model=json.obj("model")
            require(model.text("type")=="WordPiece") { "当前本地分词器支持BERT WordPiece" }
            vocab=model.obj("vocab").mapValues { it.value.jsonPrimitive.int }
            lowercase=json.obj("normalizer")["lowercase"]?.jsonPrimitive?.booleanOrNull ?: true
        } else { vocab=file.readLines().mapIndexed { i,s -> s to i }.toMap(); lowercase=true }
        require(listOf("[UNK]","[CLS]","[SEP]").all { it in vocab }) { "分词词表缺少BERT特殊token" }
    }
    fun encode(text:String):LongArray {
        val normalized=Normalizer.normalize(if(lowercase) text.lowercase(java.util.Locale.ROOT) else text,Normalizer.Form.NFD).filterNot { Character.getType(it)==Character.NON_SPACING_MARK.toInt() }
        val tokens=mutableListOf<String>(); val latin=StringBuilder()
        fun flush() { if(latin.isNotEmpty()) { tokens.add(latin.toString()); latin.setLength(0) } }
        normalized.codePoints().forEach { cp ->
            val type=Character.getType(cp)
            when {
                Character.isWhitespace(cp) || type==Character.CONTROL.toInt() -> flush()
                Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN || type in setOf(Character.CONNECTOR_PUNCTUATION.toInt(),Character.DASH_PUNCTUATION.toInt(),Character.START_PUNCTUATION.toInt(),Character.END_PUNCTUATION.toInt(),Character.OTHER_PUNCTUATION.toInt(),Character.MATH_SYMBOL.toInt(),Character.CURRENCY_SYMBOL.toInt()) -> { flush(); tokens.add(String(Character.toChars(cp))) }
                else -> latin.appendCodePoint(cp)
            }
        }; flush()
        val ids=mutableListOf(vocab.getValue("[CLS]").toLong())
        for(token in tokens) {
            if(ids.size>=maxTokens-1) break
            var start=0; val pieces=mutableListOf<Int>(); var failed=token.length>100
            while(start<token.length && !failed) {
                var end=token.length; var found:Int?=null
                while(end>start) { val piece=(if(start>0) "##" else "")+token.substring(start,end); found=vocab[piece]; if(found!=null) break; end-- }
                if(found==null) failed=true else { pieces.add(found); start=end }
            }
            val output=if(failed) listOf(vocab.getValue("[UNK]")) else pieces
            for(piece in output) { if(ids.size>=maxTokens-1) break; ids.add(piece.toLong()) }
        }
        ids.add(vocab.getValue("[SEP]").toLong()); return ids.toLongArray()
    }
}
