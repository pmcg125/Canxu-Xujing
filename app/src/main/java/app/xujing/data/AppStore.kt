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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
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

/** Per-record encrypted SQLite persistence. Records are gzipped and split into 1 MiB chunks. */
class AppStore(context: Context, private val vault: SecretVault) : SQLiteOpenHelper(context, "xujing.db", null, 2) {
    private val cache = mutableMapOf<String, String>()
    override fun onCreate(db: SQLiteDatabase) {
        createRecords(db, "records")
        db.execSQL("CREATE INDEX records_parent ON records(kind,parent,sequence)")
        db.execSQL("CREATE TABLE vectors (profile TEXT NOT NULL, session TEXT NOT NULL, chunk TEXT NOT NULL, text TEXT NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(profile,session,chunk))")
    }

    private fun createRecords(db: SQLiteDatabase, name: String) {
        db.execSQL("CREATE TABLE $name (kind TEXT NOT NULL, id TEXT NOT NULL, parent TEXT, sequence INTEGER NOT NULL DEFAULT 0, chunk INTEGER NOT NULL DEFAULT 0, payload BLOB NOT NULL, PRIMARY KEY(kind,id,chunk))")
    }

    /** v1 stored one sealed row per record; v2 compresses and chunks it so CursorWindow limits cannot be hit. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) migrateToChunked(db)
    }

    private fun migrateToChunked(db: SQLiteDatabase) {
        val keys = mutableListOf<Triple<String, String, Pair<String?, Int>>>()
        db.rawQuery("SELECT kind,id,parent,sequence FROM records ORDER BY sequence", null).use { c ->
            while (c.moveToNext()) keys += Triple(c.getString(0), c.getString(1), (if (c.isNull(2)) null else c.getString(2)) to c.getInt(3))
        }
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS records_v2")
            createRecords(db, "records_v2")
            for ((kind, id, meta) in keys) {
                val plain = vault.open(readLegacyPayload(db, kind, id))
                insertSealed(db, "records_v2", kind, id, meta.first, meta.second, vault.seal(gzip(plain)))
            }
            db.execSQL("DROP TABLE records")
            db.execSQL("ALTER TABLE records_v2 RENAME TO records")
            db.execSQL("CREATE INDEX records_parent ON records(kind,parent,sequence)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * v1 read path, only used while migrating. substr() streams rows larger than a CursorWindow.
     */
    private fun readLegacyPayload(db: SQLiteDatabase, kind: String, id: String): ByteArray {
        val length = db.rawQuery("SELECT length(payload) FROM records WHERE kind=? AND id=?", arrayOf(kind, id)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L
        }
        require(length >= 0L) { "本地记录缺失：$kind/$id" }
        if (length == 0L) return ByteArray(0)
        val slice = 512 * 1024L
        if (length <= slice) {
            return db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=?", arrayOf(kind, id)).use { c ->
                require(c.moveToFirst()) { "本地记录缺失：$kind/$id" }
                c.getBlob(0)
            }
        }
        val output = ByteArrayOutputStream(length.toInt())
        var offset = 1L
        while (offset <= length) {
            val part = db.rawQuery("SELECT substr(payload,$offset,$slice) FROM records WHERE kind=? AND id=?", arrayOf(kind, id)).use { c ->
                require(c.moveToFirst()) { "本地记录缺失：$kind/$id" }
                c.getBlob(0)
            }
            if (part.isEmpty()) break
            output.write(part)
            offset += part.size
        }
        return output.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    private fun insertSealed(db: SQLiteDatabase, table: String, kind: String, id: String, parent: String?, sequence: Int, sealed: ByteArray) {
        var index = 0
        var offset = 0
        while (offset < sealed.size) {
            val end = minOf(offset + CHUNK_SIZE, sealed.size)
            require(db.insertWithOnConflict(table, null, ContentValues().apply {
                put("kind", kind); put("id", id); put("parent", parent); put("sequence", sequence)
                put("chunk", index); put("payload", sealed.copyOfRange(offset, end))
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存本地资料失败" }
            offset = end; index++
        }
    }

    private fun readRecord(db: SQLiteDatabase, kind: String, id: String): ByteArray {
        val output = ByteArrayOutputStream()
        db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=? ORDER BY chunk", arrayOf(kind, id)).use { c ->
            while (c.moveToNext()) output.write(c.getBlob(0))
        }
        return output.toByteArray()
    }

    @Synchronized fun load(): AppState? {
        val rows = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val keys = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT kind,id FROM records WHERE chunk=0 ORDER BY sequence", null).use { c ->
            while(c.moveToNext()) keys += c.getString(0) to c.getString(1)
        }
        val db = readableDatabase
        for ((kind, id) in keys) {
            val text = gunzip(vault.open(readRecord(db, kind, id))).toString(Charsets.UTF_8)
            cache["$kind/$id"] = text
            rows.getOrPut(kind) { mutableListOf() }.add(id to text)
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
            records.forEach { (compound,r) ->
                if(cache[compound] != r.payload) {
                    db.delete("records","kind=? AND id=?",arrayOf(r.kind,r.id))
                    insertSealed(db,"records",r.kind,r.id,r.parent,r.seq,vault.seal(gzip(r.payload.toByteArray())))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        cache.clear(); records.forEach { (key,value) -> cache[key] = value.payload }
    }
    @kotlinx.serialization.Serializable private data class StoredMessage(val sessionId:String,val sequence:Int,val message:ChatMessage)
    private data class Record(val kind:String,val id:String,val parent:String?,val seq:Int,val payload:String)
    private companion object { const val CHUNK_SIZE = 1 shl 20 }

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
