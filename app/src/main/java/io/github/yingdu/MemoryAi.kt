package io.github.yingdu

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 转文字，两种接口：
 *  - OpenAI 兼容的 /audio/transcriptions（multipart 上传音频，返回 {"text": ...}）：硅基流动（SenseVoice）、OpenAI、Groq 等；
 *  - 阿里云百炼的 Qwen3-ASR（qwen3-asr-flash）：走 OpenAI 兼容的 /chat/completions，音频用 base64 放在
 *    user 消息的 input_audio 里，识别选项放在 asr_options，返回的 choices[0].message.content 就是文字
 *    （单段音频不超过 3 分钟、10 MB；萤读每段最长 2 分钟）。
 */
object SpeechToText {
    class Preset(val id: String, val name: String, val base: String, val model: String, val note: String)

    val PRESETS = listOf(
        Preset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall", "SenseVoice，中文好，免费"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-transcribe", ""),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "whisper-large-v3", "Whisper large-v3，有免费额度"),
        Preset("qwen", "阿里百炼（国内）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash", "Qwen3-ASR-Flash，中文准、抗噪"),
        Preset("qwen-intl", "阿里百炼（国际）", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash", "国际站账号用这个"),
        Preset("custom", "自定义", "", "", "OpenAI 兼容的 /audio/transcriptions"),
    )

    /** Whisper / OpenAI 的模型认 prompt：给一句简体中文带标点的例子，输出就是简体、有标点（不然常常是繁体、没标点）。 */
    const val ZH_PROMPT = "以下是普通话的对话，使用简体中文，并加上标点符号。"
    fun wantsPrompt(model: String, language: String) =
        language != "en" && (model.contains("whisper", true) || model.contains("transcribe", true))

    /** 构造 multipart 请求体（单独拿出来，方便单元测试）。 */
    fun multipart(boundary: String, model: String, language: String, fileName: String, mime: String, audio: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
        }
        field("model", model)
        if (language.isNotBlank()) field("language", language)
        if (wantsPrompt(model, language)) field("prompt", ZH_PROMPT)
        field("response_format", "json")
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(audio)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** 从返回里取文字；SenseVoice 会带 <|zh|><|NEUTRAL|> 这类标记，去掉。 */
    fun parseText(body: String): String {
        val t = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
        return t.replace(Regex("<\\|[^|>]*\\|>"), "").trim()
    }

    /** Qwen3-ASR 这类用对话接口的模型。 */
    fun isChatAsr(model: String) = model.startsWith("qwen", true) && model.contains("asr", true)

    /** Qwen3-ASR 的请求体（单独拿出来，方便单元测试）。 */
    fun chatBody(model: String, language: String, mime: String, audio: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(audio)
        val audioPart = JSONObject().put("type", "input_audio")
            .put("input_audio", JSONObject().put("data", "data:$mime;base64,$b64"))
        val opts = JSONObject().put("enable_itn", false)
        if (language.isNotBlank()) opts.put("language", language) else opts.put("enable_lid", true)
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audioPart))))
            .put("stream", false)
            .put("asr_options", opts)
            .toString()
    }

    /** 取 choices[0].message.content（字符串，或者 [{"text": ...}] 这种数组）。 */
    fun parseChat(body: String): String {
        val msg = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val c = msg.opt("content")
        val t = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: c.optString(it) }
            else -> ""
        }
        return t.replace(Regex("<\\|[^|>]*\\|>"), "").trim()
    }

    fun transcribe(base: String, key: String, model: String, language: String, fileName: String, mime: String, audio: ByteArray): String {
        if (isChatAsr(model)) return transcribeChat(base, key, model, language, mime, audio)
        val boundary = "yingdu" + System.nanoTime()
        val body = multipart(boundary, model, language, fileName, mime, audio)
        val c = URL(base.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return parseText(text)
        } finally { c.disconnect() }
    }

    private fun transcribeChat(base: String, key: String, model: String, language: String, mime: String, audio: ByteArray): String {
        val body = chatBody(model, language, mime, audio).toByteArray(Charsets.UTF_8)
        val c = URL(base.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setFixedLengthStreamingMode(body.size)
        try {
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return parseChat(text)
        } finally { c.disconnect() }
    }
}

/**
 * 每天的总结：把当天转好的文字交给大模型，得到"概要、聊了什么、待办、值得记住的"。
 *  - Claude：Anthropic Messages API（POST /v1/messages）。萤读的打包工具链（kotlinc + dx，没有 Gradle）
 *    装不下官方 Java SDK 的依赖（OkHttp、Jackson、新版 Kotlin），所以按官方文档的 HTTP 格式直接调用：
 *    模型 claude-opus-5，结构化输出（output_config.format = json_schema），
 *    打开服务端的拒答兜底（fallbacks: "default"，beta 头 server-side-fallback-2026-07-01），并检查 stop_reason。
 *  - OpenAI 兼容的 /chat/completions（硅基流动、DeepSeek、通义千问、OpenAI……），用 JSON 模式。
 */
object DaySummarizer {
    class Preset(val id: String, val name: String, val base: String, val model: String)

    val PRESETS = listOf(
        Preset("claude", "Claude", "https://api.anthropic.com", "claude-opus-5"),
        Preset("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("custom", "自定义（OpenAI 兼容）", "", ""),
    )

    const val SYSTEM = "你是用户的私人记忆助手。用户戴着智能眼镜，眼镜麦克风录下了他今天说话时的声音，已经自动转成文字，按时间排列。" +
        "转写可能有错字，也会混进旁边其他人说的话、电视或视频的声音。请整理成一份简短的中文日终回顾：" +
        "overview 用两三句话概括今天；topics 列出聊过的主要话题（每条一句话，最多 8 条）；" +
        "todos 列出用户答应别人的事、需要去做的事（text 写清楚做什么，when 写时间要求，没有就留空），没有就给空数组；" +
        "notes 列出值得记住的具体信息，比如人名、数字、地点、约定（最多 8 条）。" +
        "只根据文字里确实出现的内容写，不要编造；听不清、明显是噪声的片段忽略。" +
        "title 给这一天起个标题：像朋友吐槽一样，轻松、带点自嘲和幽默，概括今天最有代表性的一两件事，" +
        "两个短句用逗号隔开、每句 5 到 8 个字，例如「社保窗口连轴转，车辆警报频触发」「AI象棋外挂开源，眼镜评测刷屏」，不要书名号和句号；" +
        "mood 用一个 emoji 表示这一天的整体心情（比如 😄 😊 😐 😮‍💨 😫 😡 🥳 🤔），只给一个表情。" +
        "每句转写前面标了「我」的是戴眼镜的用户本人说的，标了「他人」的是旁边的人说的（按音量和音色猜的，不一定准）；没有标的不确定是谁。"

    /** 结构化输出的 JSON Schema（所有对象 additionalProperties: false，字段都必填）。 */
    fun schema(): JSONObject = JSONObject("""
        {"type":"object","additionalProperties":false,"required":["title","mood","overview","topics","todos","notes"],
         "properties":{
           "title":{"type":"string"},
           "mood":{"type":"string"},
           "overview":{"type":"string"},
           "topics":{"type":"array","items":{"type":"string"}},
           "todos":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["text","when"],
                    "properties":{"text":{"type":"string"},"when":{"type":"string"}}}},
           "notes":{"type":"array","items":{"type":"string"}}}}
    """.trimIndent())

    /** 当天的文字，一轮一行："[HH:mm] 我：文字"（分不出是谁就不标）。 */
    fun transcriptText(entries: List<MemoryEntry>, isMe: (Speakers.Voice?) -> Boolean? = { null }): String {
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        return entries.filter { it.text.isNotBlank() }.flatMap { e ->
            e.lines(isMe).map { "[${hm.format(Date(e.start))}] " + speakerTag(it.me) + it.text }
        }.joinToString("\n")
    }

    fun speakerTag(who: Boolean?) = when (who) { true -> "我："; false -> "他人："; null -> "" }

    /** Claude 的请求体。 */
    fun claudeBody(model: String, transcript: String, day: String): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", 16000)
        .put("fallbacks", "default")
        .put("system", SYSTEM)
        .put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema())))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "日期：$day\n\n今天的转写：\n$transcript")))

    /** 解析 Claude 的返回：先看 stop_reason，再取 text 块里的 JSON。 */
    fun parseClaude(body: String): JSONObject {
        val o = JSONObject(body)
        when (val stop = o.optString("stop_reason")) {
            "refusal" -> throw IOException("Claude 拒绝了这次总结（${o.optJSONObject("stop_details")?.optString("category") ?: ""}）")
            "max_tokens" -> throw IOException("总结太长被截断了")
            else -> if (stop.isEmpty() && o.has("error")) throw IOException(o.getJSONObject("error").optString("message"))
        }
        val content = o.optJSONArray("content") ?: throw IOException("返回里没有内容")
        val text = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
        return JSONObject(text)
    }

    fun openAiBody(model: String, transcript: String, day: String): JSONObject = JSONObject()
        .put("model", model)
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM +
                "\n只输出一个 JSON 对象，格式：{\"title\":\"…\",\"mood\":\"😊\",\"overview\":\"…\",\"topics\":[\"…\"],\"todos\":[{\"text\":\"…\",\"when\":\"…\"}],\"notes\":[\"…\"]}"))
            .put(JSONObject().put("role", "user").put("content", "日期：$day\n\n今天的转写：\n$transcript")))

    fun parseOpenAi(body: String): JSONObject {
        val o = JSONObject(body)
        o.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        val msg = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val t = msg.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
    }

    fun toSummary(day: String, j: JSONObject, model: String): MemorySummary {
        fun arr(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        val todos = j.optJSONArray("todos")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("text") to it.optString("when") } ?: a.optString(i).takeIf { it.isNotBlank() }?.let { it to "" } }
        }?.filter { it.first.isNotBlank() } ?: emptyList()
        val title = j.optString("title").trim().trim('「', '」', '《', '》', '"', '。')
        return MemorySummary(day, System.currentTimeMillis(), j.optString("overview"), arr("topics"), todos, arr("notes"), model,
            title, moodOf(j.optString("mood")))
    }

    /** 只留第一个表情（模型偶尔会给一串或带字）。 */
    fun moodOf(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return ""
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next()
        val first = t.substring(0, if (end == java.text.BreakIterator.DONE) t.length else end)
        val cp = first.codePointAt(0)
        val emoji = cp >= 0x1F000 || cp in 0x2300..0x23FF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF
        return if (emoji) first else ""
    }

    /** 非 Claude 的模型上下文可能较短：文字太长时分段整理再合并（不截断）。 */
    const val CHUNK_CHARS = 40_000

    fun chunks(transcript: String, max: Int = CHUNK_CHARS): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (line in transcript.lines()) {
            if (cur.isNotEmpty() && cur.length + line.length + 1 > max) { out.add(cur.toString().trimEnd('\n')); cur.setLength(0) }
            cur.append(line).append('\n')
        }
        if (cur.isNotBlank()) out.add(cur.toString().trimEnd())
        return out
    }

    /** 调用大模型（后台线程）。 */
    fun summarize(provider: String, base: String, key: String, model: String, day: String, entries: List<MemoryEntry>,
                  isMe: (Speakers.Voice?) -> Boolean? = { null }): MemorySummary {
        val transcript = transcriptText(entries, isMe)
        if (transcript.isBlank()) throw IOException("今天还没有转好的文字")
        val parts = if (provider == "claude") listOf(transcript) else chunks(transcript)
        if (parts.size == 1) return call(provider, base, key, model, day, transcript)
        // 分段：每段先整理，再把各段的整理结果合并成一份
        val partial = parts.mapIndexed { i, t -> call(provider, base, key, model, "$day（第 ${i + 1}/${parts.size} 段）", t) }
        val merged = partial.joinToString("\n\n") { it.toJson().apply { remove("generatedAt"); remove("model"); remove("day") }.toString() }
        return call(provider, base, key, model, day, "（以下是今天分段整理好的回顾，请合并成一份，去掉重复）\n$merged").let {
            MemorySummary(day, it.generatedAt, it.overview, it.topics, it.todos, it.notes, it.model, it.title, it.mood)
        }
    }

    private fun call(provider: String, base: String, key: String, model: String, day: String, transcript: String): MemorySummary {
        val claude = provider == "claude"
        val text = post(provider, base, key, if (claude) claudeBody(model, transcript, day) else openAiBody(model, transcript, day))
        return toSummary(day, if (claude) parseClaude(text) else parseOpenAi(text), model)
    }

    /** 发请求、返回响应正文（后台线程）；Claude 走 /v1/messages，其他走 OpenAI 兼容的 /chat/completions。 */
    fun post(provider: String, base: String, key: String, body: JSONObject): String {
        val claude = provider == "claude"
        val url = if (claude) base.trimEnd('/') + "/v1/messages" else base.trimEnd('/') + "/chat/completions"
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 600_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (claude) {
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("anthropic-beta", "server-side-fallback-2026-07-01")
        } else c.setRequestProperty("Authorization", "Bearer $key")
        c.setFixedLengthStreamingMode(bytes.size)
        try {
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IOException("HTTP $code ${msg ?: text.take(200)}")
            }
            return text
        } finally { c.disconnect() }
    }
}

/**
 * 问问记忆：把问题和相关材料（每天的回顾 + 最相关的原话，见 [MemorySearch.askContext]）交给总结用的那个大模型，
 * 让它只根据材料回答，并注明日期时间。用的是「全天记忆设置」里总结的服务和 Key。
 */
object MemoryAsk {
    fun system(today: String) = "你是用户的私人记忆助手。用户戴着智能眼镜，眼镜麦克风录下了他说话时的声音并自动转成文字。" +
        "下面给你每天的回顾摘要和一些原话片段（转写可能有错字，也会混进旁边其他人、电视或视频的声音）。" +
        "请只根据这些材料回答用户的问题：用中文，简洁（一般不超过 200 字），在答案里写明依据的日期和时间（比如 9月28日 14:05）。" +
        "材料里找不到答案就直接说没找到，不要编造。今天是 $today。"

    fun claudeBody(model: String, today: String, question: String, context: String): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", 8000)
        .put("fallbacks", "default")
        .put("system", system(today))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "材料：\n$context\n\n问题：$question")))

    fun openAiBody(model: String, today: String, question: String, context: String): JSONObject = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system(today)))
            .put(JSONObject().put("role", "user").put("content", "材料：\n$context\n\n问题：$question")))

    /** 取回答的文字：Claude 先看 stop_reason，再把 text 块拼起来；OpenAI 取第一条消息。 */
    fun parse(provider: String, body: String): String {
        val o = JSONObject(body)
        if (provider == "claude") {
            when (o.optString("stop_reason")) {
                "refusal" -> throw IOException("Claude 拒绝回答（${o.optJSONObject("stop_details")?.optString("category") ?: ""}）")
                else -> if (!o.has("content") && o.has("error")) throw IOException(o.getJSONObject("error").optString("message"))
            }
            val content = o.optJSONArray("content") ?: throw IOException("返回里没有内容")
            return (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }.trim()
        }
        o.optJSONObject("error")?.let { throw IOException(it.optString("message")) }
        return o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    /** 调用大模型（后台线程）。 */
    fun ask(provider: String, base: String, key: String, model: String, today: String, question: String, context: String): String {
        if (context.isBlank()) throw IOException("还没有可以查的记忆（没有转好的文字，也没有每天的总结）")
        val body = if (provider == "claude") claudeBody(model, today, question, context) else openAiBody(model, today, question, context)
        return parse(provider, DaySummarizer.post(provider, base, key, body)).ifEmpty { throw IOException("模型没有给出回答") }
    }
}

