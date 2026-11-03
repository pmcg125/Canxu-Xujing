package app.xujing.core.compat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounds are deliberately applied to expanded bytes, not only archive file size. */
internal object ExchangeLimits {
    const val MAX_FILE = 256 * 1024 * 1024
    const val MAX_EXPANDED = 384 * 1024 * 1024
    const val MAX_ENTRY = 64 * 1024 * 1024
    const val MAX_JSON = 32 * 1024 * 1024
    const val MAX_LINE = 8 * 1024 * 1024
    const val MAX_ENTRIES = 20_000

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    fun readBounded(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maximum) { "文件展开后超过大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun safePath(value: String): String {
        require(value.isNotEmpty() && value.length <= 1024) { "归档路径无效" }
        require(!value.startsWith('/') && !value.contains('\\') && !value.contains(':')) { "归档路径必须是相对路径" }
        require(value.none { it.code < 32 || it.code == 127 }) { "归档路径含控制字符" }
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "归档路径含越界或空目录" }
        return value
    }
}

internal object PngCards {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val keywords = setOf("chara", "ccv3")
    private data class Chunk(val type: String, val data: ByteArray)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && signature.indices.all { bytes[it] == signature[it] }

    private fun chunks(bytes: ByteArray): List<Chunk> {
        require(isPng(bytes)) { "不是有效 PNG 文件" }
        val result = mutableListOf<Chunk>()
        var offset = 8
        var ended = false
        while (offset + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int.toLong() and 0xffffffffL
            require(length <= ExchangeLimits.MAX_ENTRY && offset.toLong() + 12 + length <= bytes.size) { "PNG 数据块损坏或过大" }
            val count = length.toInt()
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val type = typeBytes.toString(Charsets.US_ASCII)
            require(type.length == 4 && type.all { it in 'A'..'Z' || it in 'a'..'z' }) { "PNG 数据块类型无效" }
            val data = bytes.copyOfRange(offset + 8, offset + 8 + count)
            val expected = ByteBuffer.wrap(bytes, offset + 8 + count, 4).int.toLong() and 0xffffffffL
            val crc = CRC32().apply { update(typeBytes); update(data) }.value
            require(crc == expected) { "PNG 数据块校验失败：$type" }
            result += Chunk(type, data)
            require(result.size <= ExchangeLimits.MAX_ENTRIES) { "PNG 数据块数量过多" }
            offset += 12 + count
            if (type == "IEND") { require(count == 0); ended = true; break }
        }
        require(ended && result.firstOrNull()?.type == "IHDR" && result.first().data.size == 13) { "PNG 结构不完整" }
        require(result.any { it.type == "IDAT" }) { "PNG 缺少图像数据" }
        return result
    }

    private fun text(chunk: Chunk): Pair<String, String>? {
        val zero = chunk.data.indexOf(0)
        if (zero !in 1..79) return null
        val key = chunk.data.copyOfRange(0, zero).toString(Charsets.ISO_8859_1).lowercase()
        if (key !in keywords) return null
        val bytes = chunk.data
        val value = when (chunk.type) {
            "tEXt" -> bytes.copyOfRange(zero + 1, bytes.size)
            "zTXt" -> {
                require(zero + 2 < bytes.size && bytes[zero + 1].toInt() == 0) { "PNG zTXt 压缩格式不支持" }
                InflaterInputStream(ByteArrayInputStream(bytes, zero + 2, bytes.size - zero - 2)).use {
                    ExchangeLimits.readBounded(it, ExchangeLimits.MAX_JSON)
                }
            }
            "iTXt" -> {
                require(zero + 3 < bytes.size) { "PNG iTXt 数据损坏" }
                val compressed = bytes[zero + 1].toInt()
                require(compressed in 0..1 && bytes[zero + 2].toInt() == 0) { "PNG iTXt 压缩格式不支持" }
                var position = zero + 3
                repeat(2) {
                    while (position < bytes.size && bytes[position] != 0.toByte()) position++
                    require(position < bytes.size) { "PNG iTXt 数据损坏" }
                    position++
                }
                val content = bytes.copyOfRange(position, bytes.size)
                if (compressed == 1) InflaterInputStream(ByteArrayInputStream(content)).use {
                    ExchangeLimits.readBounded(it, ExchangeLimits.MAX_JSON)
                } else content
            }
            else -> return null
        }
        require(value.size <= ExchangeLimits.MAX_JSON) { "角色卡文本过大" }
        return key to ExchangeLimits.utf8(value)
    }

    /** PiuPiu gives chara priority. The other payload is returned separately for preservation. */
    fun metadata(bytes: ByteArray): Map<String, String> = buildMap {
        chunks(bytes).forEach { chunk -> if (chunk.type in setOf("tEXt", "iTXt", "zTXt")) text(chunk)?.let { put(it.first, it.second) } }
    }

    fun insert(bytes: ByteArray?, chara: String, ccv3: String? = null): ByteArray {
        val source = chunks(bytes ?: placeholder)
        val output = ByteArrayOutputStream()
        output.write(signature)
        DataOutputStream(output).use { writer ->
            fun writeChunk(type: String, data: ByteArray) {
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                writer.writeInt(data.size); writer.write(typeBytes); writer.write(data)
                writer.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
            }
            source.forEach { chunk ->
                val isCard = chunk.type in setOf("tEXt", "iTXt", "zTXt") &&
                    chunk.data.indexOf(0).let { zero -> zero in 1..79 && chunk.data.copyOfRange(0, zero).toString(Charsets.ISO_8859_1).lowercase() in keywords }
                if (chunk.type == "IEND") {
                    writeChunk("tEXt", "chara\u0000$chara".toByteArray(Charsets.UTF_8))
                    ccv3?.let { writeChunk("tEXt", "ccv3\u0000$it".toByteArray(Charsets.UTF_8)) }
                }
                if (!isCard) writeChunk(chunk.type, chunk.data)
            }
        }
        return output.toByteArray()
    }

    // A one-pixel transparent PNG generated from the PNG specification, without external artwork.
    private val placeholder: ByteArray by lazy {
        val output = ByteArrayOutputStream()
        output.write(signature)
        DataOutputStream(output).use { writer ->
            fun write(type: String, data: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                writer.writeInt(data.size); writer.write(name); writer.write(data)
                writer.writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
            }
            write("IHDR", ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(6).put(0).put(0).put(0).array())
            val pixel = ByteArrayOutputStream().also { target -> java.util.zip.DeflaterOutputStream(target).use { it.write(byteArrayOf(0, 0, 0, 0, 0)) } }.toByteArray()
            write("IDAT", pixel); write("IEND", byteArrayOf())
        }
        output.toByteArray()
    }
}

internal object ExchangeZip {
    private data class Expected(val size: Long, val crc: Long, val method: Int)

    private fun directory(bytes: ByteArray): Map<String, Expected> {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun uint(offset: Int) = data.getInt(offset).toLong() and 0xffffffffL
        fun ushort(offset: Int) = data.getShort(offset).toInt() and 0xffff
        var end = bytes.size - 22
        val minimum = maxOf(0, bytes.size - 65557)
        while (end >= minimum && !(uint(end) == 0x06054b50L && end + 22 + ushort(end + 20) == bytes.size)) end--
        require(end >= minimum) { "ZIP 中央目录缺失或归档截断" }
        require(ushort(end + 4) == 0 && ushort(end + 6) == 0) { "不支持分卷 ZIP" }
        val count = ushort(end + 10)
        require(count == ushort(end + 8) && count < 65535 && count <= ExchangeLimits.MAX_ENTRIES) { "ZIP 条目数过多或不支持 ZIP64" }
        val start = uint(end + 16)
        val length = uint(end + 12)
        require(start + length == end.toLong()) { "ZIP 中央目录位置不一致" }
        var offset = start.toInt()
        var total = 0L
        val result = linkedMapOf<String, Expected>()
        repeat(count) {
            require(offset + 46 <= end && uint(offset) == 0x02014b50L) { "ZIP 中央目录损坏" }
            val flags = ushort(offset + 8)
            require(flags and 0x41 == 0) { "不支持加密 ZIP" }
            val method = ushort(offset + 10)
            require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) { "ZIP 压缩算法不支持" }
            val compressed = uint(offset + 20)
            val size = uint(offset + 24)
            val nameSize = ushort(offset + 28)
            val extraSize = ushort(offset + 30)
            val commentSize = ushort(offset + 32)
            require(compressed != 0xffffffffL && size <= ExchangeLimits.MAX_ENTRY && uint(offset + 42) != 0xffffffffL) { "ZIP 条目过大或不支持 ZIP64" }
            require(offset.toLong() + 46 + nameSize + extraSize + commentSize <= end) { "ZIP 目录记录截断" }
            require(ushort(offset + 34) == 0) { "不支持分卷 ZIP" }
            val mode = (uint(offset + 38) ushr 16).toInt()
            require(mode and 0xf000 != 0xa000) { "不支持 ZIP 符号链接" }
            val nameBytes = bytes.copyOfRange(offset + 46, offset + 46 + nameSize)
            val name = ExchangeLimits.utf8(nameBytes)
            val checkedName = if (name.endsWith('/')) name.dropLast(1) else name
            ExchangeLimits.safePath(checkedName)
            require(!result.containsKey(name)) { "ZIP 包含重名文件" }
            total += size
            require(total <= ExchangeLimits.MAX_EXPANDED) { "ZIP 展开总大小超出限制" }
            result[name] = Expected(size, uint(offset + 16), method)
            offset += 46 + nameSize + extraSize + commentSize
        }
        require(offset == end) { "ZIP 中央目录长度不匹配" }
        return result
    }

    fun read(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= ExchangeLimits.MAX_FILE) { "ZIP 文件超过 256 MiB" }
        val expected = directory(bytes)
        val result = linkedMapOf<String, ByteArray>()
        val visited = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(visited.add(entry.name)) { "ZIP 包含重名文件" }
                val info = requireNotNull(expected[entry.name]) { "ZIP 本地与中央文件列表不一致" }
                require(entry.method == info.method) { "ZIP 压缩参数不一致" }
                val content = ExchangeLimits.readBounded(zip, minOf(ExchangeLimits.MAX_ENTRY.toLong(), info.size).toInt())
                total += content.size
                require(total <= ExchangeLimits.MAX_EXPANDED && content.size.toLong() == info.size) { "ZIP 文件长度不匹配" }
                require(CRC32().apply { update(content) }.value == info.crc) { "ZIP 文件校验失败" }
                if (!entry.isDirectory) result[entry.name] = content
                zip.closeEntry()
            }
        }
        require(visited == expected.keys) { "ZIP 本地文件缺失" }
        return result
    }

    fun write(files: Map<String, ByteArray>): ByteArray {
        require(files.size <= ExchangeLimits.MAX_ENTRIES)
        require(files.values.sumOf { it.size.toLong() } <= ExchangeLimits.MAX_EXPANDED) { "备份总大小超出限制" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, Charsets.UTF_8).use { zip ->
            files.forEach { (name, bytes) ->
                ExchangeLimits.safePath(name)
                require(bytes.size <= ExchangeLimits.MAX_ENTRY) { "单个备份文件超过 64 MiB" }
                // STORED mirrors the original backup format and avoids native decompressor requirements.
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value; time = 0
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
                require(output.size() <= ExchangeLimits.MAX_FILE) { "备份文件超过 256 MiB" }
            }
        }
        require(output.size() <= ExchangeLimits.MAX_FILE) { "备份文件超过 256 MiB" }
        return output.toByteArray()
    }
}
