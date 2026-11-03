package app.xujing.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.xujing.core.*
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretVault(context: Context) {
    private val prefs = context.getSharedPreferences("connection-secrets", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("xujing-local-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("xujing-local-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun seal(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(bytes)
    }
    fun open(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "本地数据为空" }
        val size = bytes[0].toInt() and 255
        require(size in 12..16 && bytes.size > size + 1) { "本地数据格式无效" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
        }.doFinal(bytes.copyOfRange(size + 1, bytes.size))
    }
    fun put(id: String, value: String) { prefs.edit().putString(id, Base64.encodeToString(seal(value.toByteArray()), Base64.NO_WRAP)).apply() }
    fun get(id: String): String = prefs.getString(id, null)?.let { open(Base64.decode(it, Base64.NO_WRAP)).toString(Charsets.UTF_8) } ?: ""
    fun delete(id: String) { prefs.edit().remove(id).apply() }
}

/** Per-record encrypted SQLite persistence. Stream deltas remain in memory until a reply ends. */
class AppStore(context: Context, private val vault: SecretVault) : SQLiteOpenHelper(context, "xujing.db", null, 1) {
    private val cache = mutableMapOf<String, String>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (kind TEXT NOT NULL, id TEXT NOT NULL, parent TEXT, sequence INTEGER NOT NULL DEFAULT 0, payload BLOB NOT NULL, PRIMARY KEY(kind,id))")
        db.execSQL("CREATE INDEX records_parent ON records(kind,parent,sequence)")
        db.execSQL("CREATE TABLE vectors (profile TEXT NOT NULL, session TEXT NOT NULL, chunk TEXT NOT NULL, text TEXT NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(profile,session,chunk))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun load(): AppState? {
        val rows = mutableMapOf<String, MutableList<Pair<String, String>>>()
        readableDatabase.rawQuery("SELECT kind,id,payload FROM records ORDER BY sequence", null).use { c ->
            while(c.moveToNext()) {
                val kind = c.getString(0); val id = c.getString(1)
                val text = vault.open(c.getBlob(2)).toString(Charsets.UTF_8)
                cache["$kind/$id"] = text
                rows.getOrPut(kind) { mutableListOf() }.add(id to text)
            }
        }
        val metadata = rows["state"]?.firstOrNull()?.second ?: return null
        val base = AppJson.decodeFromString<AppState>(metadata)
        val messages = rows["message"].orEmpty().map { AppJson.decodeFromString<StoredMessage>(it.second) }.groupBy { it.sessionId }
        return base.copy(
            roles = rows["role"].orEmpty().map { AppJson.decodeFromString<RoleCard>(it.second) },
            sessions = rows["session"].orEmpty().map { AppJson.decodeFromString<ChatSession>(it.second) }.map { it.copy(messages = messages[it.id].orEmpty().sortedBy { m -> m.sequence }.map { m -> m.message }) },
            library = rows["library"].orEmpty().map { AppJson.decodeFromString<LibraryDocument>(it.second) },
        )
    }
    @Synchronized fun save(state: AppState) {
        val records = linkedMapOf<String, Record>()
        fun add(kind: String, id: String, payload: String, parent: String? = null, seq: Int = 0) { records["$kind/$id"] = Record(kind,id,parent,seq,payload) }
        add("state", "main", AppJson.encodeToString(state.copy(roles = emptyList(),sessions = emptyList(),library = emptyList())))
        state.roles.forEach { add("role",it.id,AppJson.encodeToString(it)) }
        state.library.forEach { add("library",it.id,AppJson.encodeToString(it)) }
        state.sessions.forEach { s ->
            add("session",s.id,AppJson.encodeToString(s.copy(messages = emptyList())))
            s.messages.forEachIndexed { i,m -> add("message",m.id,AppJson.encodeToString(StoredMessage(s.id,i,m)),s.id,i) }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            (cache.keys - records.keys).forEach { compound -> val p=compound.indexOf('/'); db.delete("records", "kind=? AND id=?", arrayOf(compound.take(p),compound.drop(p+1))) }
            records.forEach { (compound,r) -> if(cache[compound] != r.payload) require(db.insertWithOnConflict("records",null,ContentValues().apply {
                put("kind",r.kind); put("id",r.id); put("parent",r.parent); put("sequence",r.seq); put("payload",vault.seal(r.payload.toByteArray()))
            },SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存本地资料失败" } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        cache.clear(); records.forEach { (key,value) -> cache[key] = value.payload }
    }
    @kotlinx.serialization.Serializable private data class StoredMessage(val sessionId:String,val sequence:Int,val message:ChatMessage)
    private data class Record(val kind:String,val id:String,val parent:String?,val seq:Int,val payload:String)

    @Synchronized fun vectorRows(profile: String, sessionId: String): List<VectorRow> = buildList {
        readableDatabase.rawQuery("SELECT chunk,text,vector FROM vectors WHERE profile=? AND session=?",arrayOf(profile,sessionId)).use { c ->
            while(c.moveToNext()) {
                val bytes=vault.open(c.getBlob(2)); val b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                add(VectorRow(c.getString(0),vault.open(Base64.decode(c.getString(1),Base64.NO_WRAP)).toString(Charsets.UTF_8),FloatArray(bytes.size/4) { b.float }))
            }
        }
    }
    @Synchronized fun putVector(profile: String, sessionId: String, row: VectorRow) {
        val b=java.nio.ByteBuffer.allocate(row.vector.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        row.vector.forEach { b.putFloat(it) }
        require(writableDatabase.insertWithOnConflict("vectors",null,ContentValues().apply {
            put("profile",profile); put("session",sessionId); put("chunk",row.id)
            put("text",Base64.encodeToString(vault.seal(row.text.toByteArray()),Base64.NO_WRAP)); put("vector",vault.seal(b.array()))
        },SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存记忆索引失败" }
    }
    @Synchronized fun deleteVectors(sessionId:String) { writableDatabase.delete("vectors","session=?",arrayOf(sessionId)) }
    @Synchronized fun deleteProfileVectors(profile:String,sessionId:String) { writableDatabase.delete("vectors","profile=? AND session=?",arrayOf(profile,sessionId)) }
    @Synchronized fun pruneVectors(profile:String,sessionId:String,ids:Set<String>) {
        val stale=mutableListOf<String>()
        readableDatabase.rawQuery("SELECT chunk FROM vectors WHERE profile=? AND session=?",arrayOf(profile,sessionId)).use { c -> while(c.moveToNext()) { val id=c.getString(0); if(id !in ids) stale.add(id) } }
        stale.forEach { writableDatabase.delete("vectors","profile=? AND session=? AND chunk=?",arrayOf(profile,sessionId,it)) }
    }
}
data class VectorRow(val id:String,val text:String,val vector:FloatArray)
