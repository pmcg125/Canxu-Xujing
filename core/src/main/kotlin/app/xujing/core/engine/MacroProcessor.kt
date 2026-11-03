package app.xujing.core.engine

import app.xujing.core.*
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

internal class MacroProcessor(
    private val state: AppState,
    private val session: ChatSession,
    private val role: RoleCard?,
    initialVariables: JsonObject,
    private val diagnostics: MutableList<String>,
    private val random: Random,
    val outlets: MutableMap<String, String> = mutableMapOf(),
) {
    var variables: JsonObject = initialVariables
        private set
    private val unknown = mutableSetOf<String>()
    private val now = LocalDateTime.now()
    private val user get() = session.userName.ifBlank { state.preferences.userName }.ifBlank { "我" }
    private val character get() = role?.name ?: "角色"

    private data class Token(val start:Int,val end:Int,val raw:String,val name:String,val args:List<String>,val closing:Boolean,val preserve:Boolean)
    private fun token(text:String,start:Int):Token? {
        if(!text.startsWith("{{",start)) return null
        var index=start+2; var depth=1
        while(index<text.length-1) {
            when {
                text.startsWith("{{",index) -> { depth++; index+=2 }
                text.startsWith("}}",index) -> { depth--; index+=2; if(depth==0) break }
                else -> index++
            }
        }
        if(depth!=0) return null
        val raw=text.substring(start,index)
        var body=raw.substring(2,raw.length-2).trim(); var preserve=false
        while(body.firstOrNull() in listOf('!','?','~','>','#')) { if(body[0]=='#') preserve=true; body=body.drop(1).trimStart() }
        val closing=body.startsWith("///") || (body.startsWith("/") && !body.startsWith("//"))
        if(closing) body=body.drop(1).trimStart()
        if(!closing && body.startsWith("//")) return Token(start,index,raw,"//",listOf(body.drop(2).removePrefix("::").trimStart()).filter(String::isNotEmpty),false,preserve)
        val parts=mutableListOf<String>(); var begin=0; var pos=0; var nested=0
        while(pos<body.length-1) {
            when {
                body.startsWith("{{",pos) -> { nested++; pos+=2 }
                body.startsWith("}}",pos) -> { nested=(nested-1).coerceAtLeast(0); pos+=2 }
                nested==0 && body.startsWith("::",pos) -> { parts+=body.substring(begin,pos); pos+=2; begin=pos }
                else -> pos++
            }
        }
        parts+=body.substring(begin)
        var name=parts.first().trim(); val args=parts.drop(1).toMutableList()
        if(args.isEmpty()) Regex("^([^\\s:]+)\\s*:?\\s+([\\s\\S]*)$").matchEntire(name)?.let { name=it.groupValues[1]; args+=it.groupValues[2] }
        return if(name.isEmpty()) null else Token(start,index,raw,name.lowercase(),args,closing,preserve)
    }
    private fun opensBlock(t:Token)=!t.closing && when(t.name) {
        "setvar","setglobalvar","if" -> t.args.size==1
        "trim","//","comment" -> t.args.isEmpty()
        else -> false
    }
    private fun blockEnd(text:String,from:Int,name:String):Token? {
        var pos=from; var depth=0
        while(pos<text.length) {
            val start=text.indexOf("{{",pos); if(start<0) return null
            val next=token(text,start) ?: return null
            if(next.name==name) {
                if(next.closing) { if(depth==0) return next; depth-- }
                else if(opensBlock(next)) depth++
            }
            pos=next.end
        }
        return null
    }
    private fun branches(text:String):Pair<String,String?> {
        var pos=0; var depth=0
        while(pos<text.length) {
            val start=text.indexOf("{{",pos); if(start<0) break
            val next=token(text,start) ?: break
            if(next.name=="if") { if(next.closing) depth-- else if(opensBlock(next)) depth++ }
            else if(next.name=="else" && !next.closing && depth==0) return text.substring(0,start) to text.substring(next.end)
            pos=next.end
        }
        return text to null
    }
    fun expand(text:String,effects:Boolean=true,escapeValues:Boolean=false):String {
        fun escape(value:String)=value.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\]""")) { "\\"+it.value }
        val input=text.replace("<USER>",if(escapeValues) escape(user) else user,true)
            .replace("<BOT>",if(escapeValues) escape(character) else character,true)
            .replace("<CHAR>",if(escapeValues) escape(character) else character,true)
        fun render(source:String,level:Int,protect:Boolean):String {
            if(level>32) { diagnostics+="宏嵌套超过 32 层，已停止展开。"; return source }
            val out=StringBuilder(); var pos=0
            while(pos<source.length) {
                val start=source.indexOf("{{",pos)
                if(start<0) { out.append(source.substring(pos)); break }
                out.append(source.substring(pos,start))
                val t=token(source,start)
                if(t==null) { out.append(source.substring(start)); break }
                if(t.closing) { out.append(t.raw); pos=t.end; continue }
                val end=if(opensBlock(t)) blockEnd(source,t.end,t.name) else null
                val scoped=end?.let { source.substring(t.end,it.start) }
                val args=when(t.name) {
                    "//","comment" -> emptyList()
                    "if" -> t.args.take(1).map { render(it,level+1,false) }
                    else -> t.args.map { render(it,level+1,false) }
                }
                val value=when {
                    t.name=="//" || t.name=="comment" -> ""
                    t.name=="if" -> {
                        var condition=args.firstOrNull().orEmpty().trim(); val invert=condition.startsWith("!")
                        if(invert) condition=condition.drop(1).trim()
                        if(condition.startsWith(".") || condition.startsWith("$")) condition=readPath(variables,(if(condition.startsWith("$")) "_global." else "")+condition.drop(1))?.plain().orEmpty()
                        if(condition.contains("{{")) t.raw+(scoped?.let { it+end!!.raw } ?: "")
                        else {
                            val truth=(condition.lowercase() !in setOf("","false","off","0")) xor invert
                            val (yes,no)=branches(scoped ?: t.args.drop(1).joinToString("::"))
                            val selected=if(truth) yes else no.orEmpty()
                            render(selected,level+1,false).let { if(t.preserve) it else it.trim() }
                        }
                    }
                    t.name=="trim" && scoped!=null -> render(scoped,level+1,false).let { if(t.preserve) it else it.trim() }
                    t.name=="trim" -> {
                        while(out.endsWith("\n")) { out.deleteCharAt(out.length-1); if(out.endsWith("\r")) out.deleteCharAt(out.length-1) }
                        var after=t.end
                        while(source.startsWith("\r\n",after) || source.startsWith("\n",after)) after+=if(source.startsWith("\r\n",after)) 2 else 1
                        pos=after; continue
                    }
                    scoped!=null && t.name in setOf("setvar","setglobalvar") -> {
                        val key=args.firstOrNull().orEmpty().trim()
                        val value=render(scoped,level+1,false).let { if(t.preserve) it else it.trim() }
                        if(effects && key.isNotEmpty()) variables=writePath(variables,(if(t.name=="setglobalvar") "_global." else "")+key,JsonPrimitive(value)).objectOrEmpty()
                        ""
                    }
                    else -> evaluate((listOf(t.name)+args).joinToString("::"),t.raw,effects)
                }
                out.append(if(protect && value!=t.raw && !value.startsWith("{{")) escape(value) else value)
                pos=end?.end ?: t.end
            }
            return out.toString()
        }
        return render(input,0,escapeValues)
    }

    private fun evaluate(body: String, original: String, effects: Boolean): String {
        val parts = body.split("::")
        val command = parts.first().lowercase()
        fun arg(i: Int = 1) = parts.getOrElse(i) { "" }.trim()
        fun change(path: String, value: JsonElement): String {
            if (effects) variables = writePath(variables, path, value).objectOrEmpty()
            return ""
        }
        return when (command) {
            "user", "username", "name1" -> user
            "char", "character", "name2" -> character
            "charid" -> role?.id.orEmpty()
            "description", "chardescription" -> role?.description.orEmpty()
            "personality", "charpersonality" -> role?.personality.orEmpty()
            "scenario" -> role?.scenario.orEmpty()
            "persona", "personadescription" -> session.persona.ifBlank { state.preferences.persona }
            "mesexamples", "dialogueexamples" -> role?.examples.orEmpty()
            "system", "systemprompt" -> role?.systemPrompt.orEmpty()
            "original" -> role?.systemPrompt.orEmpty()
            "lastmessage" -> session.messages.lastOrNull()?.content.orEmpty()
            "lastusermessage", "input" -> session.messages.lastOrNull { it.role == "user" }?.content.orEmpty()
            "lastcharmessage" -> session.messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
            "lastmessageid" -> (session.messages.size - 1).toString()
            "model" -> effectiveConnection()?.model.orEmpty()
            "maxcontext" -> (effectiveConnection()?.contextTokens ?: 16384).toString()
            "maxresponse" -> (effectiveConnection()?.parameters?.num("max_completion_tokens", "max_tokens", default = 2048) ?: 2048).toString()
            "newline" -> "\n"
            "noop", "trim", "//" -> ""
            "date" -> now.toLocalDate().toString()
            "time" -> now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            "weekday" -> now.dayOfWeek.toString()
            "datetime" -> now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            "isodate" -> now.toLocalDate().toString()
            "isotime" -> now.toLocalTime().toString()
            "getvar" -> readPath(variables, arg())?.plain().orEmpty()
            "getglobalvar" -> readPath(variables, "_global.${arg()}")?.plain().orEmpty()
            "setvar", "setglobalvar" -> change(if (command == "setglobalvar") "_global.${arg()}" else arg(), parseValue(parts.drop(2).joinToString("::")))
            "addvar", "addglobalvar" -> {
                val path=if(command=="addglobalvar") "_global.${arg()}" else arg()
                val old=readPath(variables,path); val amount=parts.drop(2).joinToString("::").trim()
                val oldNumber=(old as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.toDoubleOrNull()?.takeIf(Double::isFinite)
                val added=amount.takeIf(String::isNotEmpty)?.toDoubleOrNull()?.takeIf(Double::isFinite)
                val value=if(oldNumber!=null && added!=null) { val sum=oldNumber+added; if(sum.isFinite() && sum%1.0==0.0 && sum>=Long.MIN_VALUE.toDouble() && sum<Long.MAX_VALUE.toDouble()) JsonPrimitive(sum.toLong()) else JsonPrimitive(sum) }
                    else JsonPrimitive((old?.plain() ?: "")+amount)
                if(effects && path.isNotBlank()) variables=writePath(variables,path,value).objectOrEmpty()
                ""
            }
            "incvar", "increment", "decvar", "decrement" -> {
                val amount = when (command) { "decvar", "decrement" -> -1.0; "incvar", "increment" -> 1.0; else -> arg(2).toDoubleOrNull() ?: 0.0 }
                val old = (readPath(variables, arg()) as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val next = old + amount
                if (effects) variables = writePath(variables, arg(), if (next % 1.0 == 0.0) JsonPrimitive(next.toLong()) else JsonPrimitive(next)).objectOrEmpty()
                if (command == "addvar") "" else if (next % 1.0 == 0.0) next.toLong().toString() else next.toString()
            }
            "outlet" -> outlets[arg()].orEmpty()
            "random", "pick" -> {
                val choices = if (parts.size > 2) parts.drop(1) else arg().split(',')
                choices.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)].trim() }.orEmpty()
            }
            "roll", "dice" -> {
                val spec = Regex("(?i)^(\\d{0,3})d(\\d{1,7})([+-]\\d+)?$").matchEntire(arg())
                if (spec == null) {
                    val sides = arg().toIntOrNull()?.coerceIn(1, 1_000_000) ?: 6
                    random.nextInt(1, sides + 1).toString()
                } else {
                    val count = spec.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: 1
                    val sides = spec.groupValues[2].toInt().coerceIn(1, 1_000_000)
                    (List(count) { random.nextInt(1, sides + 1) }.sum() + (spec.groupValues[3].toIntOrNull() ?: 0)).toString()
                }
            }
            else -> {
                if (unknown.add(command)) diagnostics += "保留未支持宏 {{$command}}。"
                original
            }
        }
    }
    private fun effectiveConnection():ApiConnection? {
        val selected=state.connections.find { it.id==session.connectionId } ?: state.connections.find { it.id==state.preferences.defaultConnectionId } ?: return null
        return GenerationSettings.connection(state,session,selected)
    }
}
