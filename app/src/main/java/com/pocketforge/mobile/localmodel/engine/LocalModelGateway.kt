package com.pocketforge.mobile.localmodel.engine

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class LocalModelGateway(
    private val localEngine: LocalModelEngine,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${server.localPort}"

    fun start(): LocalModelGateway = apply {
        Thread({ acceptLoop() }, "pocket-local-model-gw").apply { isDaemon = true; start() }
    }

    private fun acceptLoop() {
        while (running.get()) {
            runCatching { server.accept() }.getOrNull()?.let { socket ->
                Thread({ socket.use(::handle) }, "pocket-local-model-req").apply { isDaemon = true; start() }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val split = line.indexOf(':')
            if (split > 0) headers[line.substring(0, split).lowercase()] = line.substring(split + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        val path = requestLine.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val output = BufferedOutputStream(socket.getOutputStream())

        if (path.endsWith("/count_tokens")) {
            val approximate = bodyBytes.decodeToString().length / 4 + 1
            writeJson(output, 200, JSONObject().put("input_tokens", approximate).toString())
            return
        }

        if (path.endsWith("/models")) {
            val active = localEngine.activeModel.value
            val modelId = active?.name ?: "local-gguf"
            val json = JSONObject().put("data", JSONArray().put(JSONObject().put("id", modelId).put("object", "model")))
            writeJson(output, 200, json.toString())
            return
        }

        if (!path.endsWith("/messages") && !path.endsWith("/chat/completions")) {
            writeJson(output, 404, errorJson("not_found", "Endpoint not found: $path"))
            return
        }

        runCatching {
            val bodyJson = JSONObject(bodyBytes.decodeToString())
            val stream = bodyJson.optBoolean("stream", false)
            val maxTokens = bodyJson.optInt("max_tokens", 1024)
            val temperature = bodyJson.optDouble("temperature", 0.7).toFloat()

            val prompt = buildPrompt(bodyJson, path.endsWith("/messages"))

            if (stream) {
                writeStreamResponse(output, prompt, maxTokens, temperature, path.endsWith("/messages"))
            } else {
                val tokens = runBlocking {
                    localEngine.generateTokens(prompt, maxTokens, temperature).toList()
                }
                val fullText = tokens.joinToString("")
                val responseJson = if (path.endsWith("/messages")) {
                    formatAnthropicMessage(fullText)
                } else {
                    formatOpenAiMessage(fullText)
                }
                writeJson(output, 200, responseJson.toString())
            }
        }.onFailure { error ->
            writeJson(output, 500, errorJson("local_engine_error", error.message ?: "Generation error"))
        }
    }

    private fun buildPrompt(body: JSONObject, isAnthropic: Boolean): String {
        val sb = StringBuilder()

        if (isAnthropic) {
            val system = body.opt("system")?.toString()?.takeIf { it.isNotBlank() }
            if (system != null) {
                sb.append("System: ").append(system).append("\n\n")
            }
            val tools = body.optJSONArray("tools")
            if (tools != null && tools.length() > 0) {
                sb.append("Available tools:\n")
                for (i in 0 until tools.length()) {
                    val t = tools.getJSONObject(i)
                    sb.append("- ").append(t.optString("name")).append(": ").append(t.optString("description")).append("\n")
                }
                sb.append("\n")
            }
            val messages = body.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val role = msg.optString("role")
                val content = msg.opt("content")
                sb.append(role.replaceFirstChar { it.uppercase() }).append(": ")
                when (content) {
                    is String -> sb.append(content)
                    is JSONArray -> {
                        for (c in 0 until content.length()) {
                            val part = content.optJSONObject(c) ?: continue
                            if (part.optString("type") == "text") {
                                sb.append(part.optString("text"))
                            } else if (part.optString("type") == "tool_result") {
                                sb.append("\n[Tool Result: ").append(part.opt("content")).append("]\n")
                            }
                        }
                    }
                }
                sb.append("\n\n")
            }
            sb.append("Assistant: ")
        } else {
            val messages = body.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val role = msg.optString("role")
                val content = msg.optString("content")
                sb.append(role.replaceFirstChar { it.uppercase() }).append(": ").append(content).append("\n\n")
            }
            sb.append("Assistant: ")
        }

        return sb.toString()
    }

    private fun writeStreamResponse(
        output: OutputStream,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        isAnthropic: Boolean,
    ) {
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()

        val msgId = "msg_${UUID.randomUUID()}"

        if (isAnthropic) {
            val startEvent = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"$msgId\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"local-gguf\"}}\n\n"
            output.write(startEvent.toByteArray(Charsets.UTF_8))
            val blockStart = "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
            output.write(blockStart.toByteArray(Charsets.UTF_8))
            output.flush()

            runBlocking {
                localEngine.generateTokens(prompt, maxTokens, temperature).collect { token ->
                    val escaped = JSONObject.quote(token)
                    val delta = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$escaped}}\n\n"
                    output.write(delta.toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }

            val blockStop = "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
            val msgDelta = "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n"
            val msgStop = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            output.write((blockStop + msgDelta + msgStop).toByteArray(Charsets.UTF_8))
            output.flush()
        } else {
            runBlocking {
                localEngine.generateTokens(prompt, maxTokens, temperature).collect { token ->
                    val chunk = JSONObject()
                        .put("id", msgId)
                        .put("object", "chat.completion.chunk")
                        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", token))))
                    output.write("data: $chunk\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
            output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun formatAnthropicMessage(text: String): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return JSONObject()
            .put("id", "msg_${UUID.randomUUID()}")
            .put("type", "message")
            .put("role", "assistant")
            .put("content", content)
            .put("model", localEngine.activeModel.value?.name ?: "local-gguf")
            .put("stop_reason", "end_turn")
    }

    private fun formatOpenAiMessage(text: String): JSONObject {
        val message = JSONObject().put("role", "assistant").put("content", text)
        val choice = JSONObject().put("index", 0).put("message", message).put("finish_reason", "stop")
        return JSONObject()
            .put("id", "chatcmpl_${UUID.randomUUID()}")
            .put("object", "chat.completion")
            .put("choices", JSONArray().put(choice))
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == '\n'.code) {
                return out.toString().trimEnd('\r')
            }
            out.append(b.toChar())
        }
    }

    private fun writeJson(output: OutputStream, status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    private fun errorJson(type: String, message: String): String {
        return JSONObject()
            .put("type", "error")
            .put("error", JSONObject().put("type", type).put("message", message))
            .toString()
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
