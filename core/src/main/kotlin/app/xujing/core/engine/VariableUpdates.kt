package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*

internal data class VariableUpdate(val text: String, val variables: JsonObject)

/** Interprets a deliberately small data-update grammar. No JavaScript is evaluated. */
internal object VariableUpdates {
    fun apply(text: String, initial: JsonObject, diagnostics: MutableList<String>): VariableUpdate {
        var variables = initial
        val jsonBlock = Regex("(?is)<\\s*json[_-]?patch\\b[^>]*>\\s*([\\s\\S]*?)\\s*</\\s*json[_-]?patch\\s*>")
        fun processJson(input:String) = jsonBlock.replace(input) { block ->
            runCatching {
                val value = AppJson.parseToJsonElement(block.groupValues[1].removeSurrounding("```json", "```").trim())
                val operations = value as? JsonArray ?: error("JSONPatch 必须是数组")
                require(operations.size <= 512) { "JSONPatch 操作超过 512 条" }
                var candidate: JsonElement = variables
                operations.forEach { operation ->
                    val op = operation as? JsonObject ?: error("补丁操作需要对象")
                    fun pointer(key:String):String = (op[key] as? JsonPrimitive)
                        ?.takeIf { it !== JsonNull && it.isString }?.content
                        ?: error("补丁 $key 必须是字符串路径")
                    val path = patchPath(candidate.objectOrEmpty(), pointer("path"))
                    val current = readPath(candidate, path)
                    candidate = when (op.str("op").lowercase()) {
                        "add" -> writePath(candidate, path, op["value"] ?: JsonNull, append = true)
                        "replace" -> { require(current != null) { "replace 目标不存在: $path" }; writePath(candidate, path, replaceValue(current, op["value"] ?: JsonNull)) }
                        "delta" -> writePath(candidate, path, addValue(current, op["value"] ?: error("delta 缺少数值")))
                        "remove" -> { require(current != null) { "remove 目标不存在: $path" }; writePath(candidate, path, null) }
                        "copy" -> writePath(candidate, path, readPath(candidate, patchPath(candidate.objectOrEmpty(), pointer("from"))) ?: error("copy 来源不存在"), append = true)
                        "move" -> {
                            val from = patchPath(candidate.objectOrEmpty(), pointer("from"))
                            require(path != from && !path.startsWith("$from/")) { "move 目标不得是来源本身或其后代" }
                            val source = readPath(candidate, from) ?: error("move 来源不存在")
                            writePath(writePath(candidate, from, null), path, source, append = true)
                        }
                        "test" -> { require(current == op["value"]) { "补丁 test 失败: $path" }; candidate }
                        else -> error("不支持的补丁操作: ${op.str("op")}")
                    }
                }
                variables = candidate as? JsonObject ?: error("会话变量根必须为对象")
                ""
            }.getOrElse { diagnostics += "变量 JSONPatch 未提交：${it.message}"; block.value }
        }
        val updateBlock = Regex("(?is)<\\s*(?:update|updatevariable|updatemvu|variables?update)\\b[^>]*>\\s*([\\s\\S]*?)\\s*</\\s*(?:update|updatevariable|updatemvu|variables?update)\\s*>")
        fun processUpdate(input:String) = updateBlock.replace(input) { block ->
            val body = block.groupValues[1].replace(Regex("(?is)<Analysis>[\\s\\S]*?</Analysis>"), "").trim()
            if (body.isBlank()) return@replace ""
            val statements = parseStatements(body) ?: MvuJsonOperations.parse(body)
            if (statements == null) {
                diagnostics += "MVU 更新包含不支持的 JavaScript 语句，保留原文且不执行。"
                return@replace block.value
            }
            runCatching {
                var candidate = variables
                require(statements.size <= 512) { "MVU 更新操作超过 512 条" }
                statements.forEach { (command, args) ->
                    require(args.isNotEmpty()) { "MVU 缺少路径" }
                    val path = mvuPath(candidate, literal(args[0]))
                    val old = readPath(candidate, path)
                    if (command == "move") {
                        require(args.size == 2) { "move 需要来源和目标" }
                        val target = mvuPath(candidate, literal(args[1]))
                        require(path != target && !target.startsWith("$path/")) { "move 目标不得是来源或其后代" }
                        if (old != null) candidate = writePath(writePath(candidate, path, null), target, old).objectOrEmpty()
                        return@forEach
                    }
                    val value = when (command) {
                        "set", "replace" -> {
                            require(args.size in 2..3)
                            replaceValue(old, literal(args.last()))
                        }
                        "add" -> {
                            require(args.size == 2)
                            addValue(old, literal(args[1]))
                        }
                        "assign" -> {
                            require(args.size in 2..3)
                            insertValue(old, if(args.size == 3) literal(args[1]) else null, literal(args.last()))
                        }
                        "insert", "push" -> {
                            require(args.size in 2..3)
                            insertValue(old, if (args.size == 3) literal(args[1]) else null, literal(args.last()))
                        }
                        "remove", "delete", "unset" -> {
                            require(args.size in 1..2)
                            if (args.size == 1) null else removeValue(old, literal(args[1]))
                        }
                        else -> error("不支持的 MVU 方法: $command")
                    }
                    candidate = writePath(candidate, path, value).objectOrEmpty()
                }
                variables = candidate
                ""
            }.getOrElse { diagnostics += "MVU 更新未提交：${it.message}"; block.value }
        }
        // Preserve the source order across patch and update blocks, as the recovered
        // parser does. Each block remains transactional in this partial interpreter.
        val orderedBlocks = Regex("(?is)<\\s*(json[_-]?patch|update|updatevariable|updatemvu|variables?update)\\b[^>]*>[\\s\\S]*?</\\s*(?:json[_-]?patch|update|updatevariable|updatemvu|variables?update)\\s*>")
        val remaining = orderedBlocks.replace(text) { block ->
            if (block.groupValues[1].startsWith("json", ignoreCase = true)) processJson(block.value)
            else processUpdate(block.value)
        }
        return VariableUpdate(remaining.trim(), variables)
    }

    private fun patchPath(root:JsonObject,path:String):String {
        if(root["stat_data"] !is JsonObject) return path
        // PiuPiu converts JSON Patch pointers to paths under stat_data.
        if(path.isEmpty() || path=="/") return "/stat_data"
        return mvuPath(root, JsonPrimitive(path))
    }

    private fun mvuPath(root:JsonObject, value:JsonElement):String {
        require(value !== JsonNull) { "变量路径不能为空" }
        val parts=when(value) {
            is JsonArray -> value.map { (it as? JsonPrimitive)?.contentOrNull ?: error("变量路径必须是字符串或数字") }
            is JsonPrimitive -> pathParts(value.content)
            else -> error("变量路径必须是字符串或数组")
        }
        require(parts.isNotEmpty() && parts.none { it in setOf("__proto__", "prototype", "constructor") }) { "变量路径无效" }
        val resolved=if(root["stat_data"] is JsonObject && !parts.first().equals("stat_data",true)) listOf("stat_data")+parts else parts
        return resolved.joinToString(separator="/", prefix="/") { it.replace("~", "~0").replace("/", "~1") }
    }

    private fun numberValue(value:Double):JsonPrimitive {
        require(value.isFinite()) { "数值结果不是有限数" }
        val rounded=java.math.BigDecimal.valueOf(value).round(java.math.MathContext(12)).stripTrailingZeros()
        return runCatching { JsonPrimitive(rounded.longValueExact()) }.getOrElse { JsonPrimitive(rounded.toDouble()) }
    }

    private fun coerceLike(old:JsonElement?,next:JsonElement):JsonElement {
        if (old is JsonPrimitive && !old.isString && old.doubleOrNull!=null && next is JsonPrimitive && next.isString)
            return numberValue(next.content.toDoubleOrNull() ?: error("数值字段不能转换为数字"))
        return next
    }

    private fun replaceValue(old:JsonElement?,next:JsonElement):JsonElement {
        val pair=old as? JsonArray
        return if(pair?.size==2 && pair[1] is JsonPrimitive && (pair[1] as JsonPrimitive).isString && pair[0] !is JsonArray)
            JsonArray(listOf(coerceLike(pair[0],next),pair[1])) else coerceLike(old,next)
    }

    private fun addValue(old:JsonElement?,value:JsonElement):JsonElement {
        val amount=value as? JsonPrimitive ?: error("add 仅支持数值")
        require(!amount.isString) { "add 仅支持数值" }
        val delta=amount.doubleOrNull ?: error("add 仅支持数值")
        val pair=old as? JsonArray
        val isPair=pair?.size==2 && pair[1] is JsonPrimitive && (pair[1] as JsonPrimitive).isString
        val number=(if(isPair) pair!![0] else old) as? JsonPrimitive ?: error("add 目标不存在或不是数值")
        require(!number.isString) { "add 目标不是数值" }
        val sum=numberValue((number.doubleOrNull ?: error("add 目标不是数值"))+delta)
        return if(isPair) JsonArray(listOf(sum,pair!![1])) else sum
    }

    private fun insertValue(old:JsonElement?, index:JsonElement?, value:JsonElement):JsonElement = when(old) {
        is JsonArray -> {
            val list=old.toMutableList()
            val n=(index as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: list.size
            list.add(if(n<0) (list.size+n).coerceAtLeast(0) else n.coerceAtMost(list.size),value)
            JsonArray(list)
        }
        is JsonObject, null -> {
            val obj=old as? JsonObject ?: EmptyObject
            if(index!=null) {
                val key=(index as? JsonPrimitive)?.contentOrNull ?: error("对象键无效")
                require(key !in setOf("__proto__","prototype","constructor")) { "对象键无效" }
                JsonObject(obj + (key to value))
            } else if(old==null && value is JsonArray) JsonArray(listOf(value))
            else mergeJson(obj,value as? JsonObject ?: error("对象插入需要对象值"))
        }
        else -> error("insert 目标不是数组或对象")
    }

    private fun removeValue(old:JsonElement?, index:JsonElement):JsonElement = when(old) {
        is JsonArray -> {
            val list=old.toMutableList()
            val numeric=(index as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val n=if(numeric!=null && numeric>=0) numeric.toInt() else list.indexOf(index)
            if(n in list.indices) list.removeAt(n)
            JsonArray(list)
        }
        is JsonObject -> {
            val primitive=index as? JsonPrimitive ?: error("对象键无效")
            val key=if (!primitive.isString) primitive.intOrNull?.let { old.keys.elementAtOrNull(it) } else primitive.contentOrNull
            if (key==null) old else JsonObject(old - key)
        }
        else -> error("delete 目标不是数组或对象")
    }

    private fun literal(value: String): JsonElement {
        val trimmed = value.trim()
        if (((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("`") && trimmed.endsWith("`"))) && trimmed.length >= 2) {
            val body = trimmed.substring(1, trimmed.lastIndex)
            val result = StringBuilder()
            var index = 0
            while (index < body.length) {
                val char = body[index++]
                if (char != '\\' || index >= body.length) result.append(char)
                else when (val escaped = body[index++]) {
                    'n' -> result.append('\n'); 'r' -> result.append('\r'); 't' -> result.append('\t')
                    '\\', '\'', '"', '`' -> result.append(escaped)
                    'u' -> { require(index + 4 <= body.length); result.append(body.substring(index, index + 4).toInt(16).toChar()); index += 4 }
                    else -> result.append(escaped)
                }
            }
            return JsonPrimitive(result.toString())
        }
        if ((trimmed.startsWith("[") || trimmed.startsWith("{")) && ('\'' in trimmed || '`' in trimmed)) {
            val normalized=StringBuilder()
            var index=0
            while(index<trimmed.length) {
                val quote=trimmed[index]
                if(quote!='\'' && quote!='`' && quote!='"') { normalized.append(quote); index++; continue }
                val start=index++
                var escaped=false
                while(index<trimmed.length) {
                    val char=trimmed[index++]
                    if(escaped) escaped=false else if(char=='\\') escaped=true else if(char==quote) break
                }
                require(index<=trimmed.length && trimmed[index-1]==quote && index-start>=2) { "字符串未闭合" }
                val token=trimmed.substring(start,index)
                normalized.append(if(quote=='"') token else literal(token).toString())
            }
            return AppJson.parseToJsonElement(normalized.toString())
        }
        return AppJson.parseToJsonElement(trimmed)
    }

    private fun parseStatements(body: String): List<Pair<String, List<String>>>? {
        // Remove comments outside quoted literals while respecting nested JSON arrays/objects.
        val methods = setOf("set", "replace", "add", "assign", "insert", "push", "remove", "delete", "unset", "move")
        val prefix = Regex("(?i)(?:_|lodash)\\s*\\.\\s*")
        val output = mutableListOf<Pair<String, List<String>>>()
        var index = 0
        var invalidComment = false
        fun whitespace() {
            while (index < body.length) {
                if (body[index].isWhitespace() || body[index] == ';') { index++; continue }
                if (body.startsWith("//", index)) { while (index < body.length && body[index] != '\n') index++; continue }
                if (body.startsWith("/*", index)) { val end = body.indexOf("*/", index + 2); if (end < 0) { invalidComment = true; index = body.length; return }; index = end + 2; continue }
                break
            }
        }
        while (index < body.length) {
            whitespace()
            if (invalidComment) return null
            if (index >= body.length) break
            val match = prefix.find(body,index)?.takeIf { it.range.first==index } ?: return null
            index=match.range.last+1
            val start = index
            while (index < body.length && body[index].isLetter()) index++
            val method = body.substring(start, index).lowercase()
            if (method !in methods) return null
            whitespace()
            if (invalidComment) return null
            if (index >= body.length || body[index++] != '(') return null
            val args = mutableListOf<String>()
            var argStart = index
            var nesting = 0
            var quote: Char? = null
            var escaped = false
            var closed = false
            while (index < body.length) {
                val char = body[index]
                if (quote != null) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == quote) quote = null
                } else when (char) {
                    '\'', '"', '`' -> quote = char
                    '[', '{', '(' -> nesting++
                    ']', '}' -> { nesting--; if (nesting < 0) return null }
                    ')' -> if (nesting == 0) {
                        if (index > argStart) args += body.substring(argStart, index).trim()
                        index++; closed = true; break
                    } else nesting--
                    ',' -> if (nesting == 0) { args += body.substring(argStart, index).trim(); argStart = index + 1 }
                }
                index++
            }
            if (!closed || quote != null) return null
            output += method to args
        }
        return output
    }
}
