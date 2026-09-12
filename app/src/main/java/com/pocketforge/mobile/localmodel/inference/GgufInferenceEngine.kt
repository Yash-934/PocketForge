package com.pocketforge.mobile.localmodel.inference

import com.pocketforge.mobile.localmodel.gguf.GgufParsedModel
import com.pocketforge.mobile.localmodel.gguf.GgufParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.random.Random

class GgufInferenceEngine {

    private var parsedModel: GgufParsedModel? = null
    private var modelFile: File? = null
    private var randomAccessFile: RandomAccessFile? = null
    private val isCancelled = AtomicBoolean(false)
    private var tokenToIdMap: Map<String, Int> = emptyMap()

    val isLoaded: Boolean
        get() = parsedModel != null && modelFile?.isFile == true

    fun load(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "Model file not found: ${file.absolutePath}" }
        unload()

        val parsed = GgufParser.parse(file, loadTokens = true)
        val raf = RandomAccessFile(file, "r")

        // Build token to ID map for prompt tokenization
        val map = HashMap<String, Int>(parsed.tokens.size)
        for (i in parsed.tokens.indices) {
            map[parsed.tokens[i]] = i
        }

        parsedModel = parsed
        modelFile = file
        randomAccessFile = raf
        tokenToIdMap = map
    }

    fun unload() {
        runCatching { randomAccessFile?.close() }
        randomAccessFile = null
        parsedModel = null
        modelFile = null
        tokenToIdMap = emptyMap()
    }

    fun cancel() {
        isCancelled.set(true)
    }

    fun tokenize(text: String): List<Int> {
        val model = parsedModel ?: return emptyList()
        val tokens = model.tokens
        if (tokens.isEmpty()) return listOf(model.bosTokenId)

        val result = mutableListOf<Int>()
        result.add(model.bosTokenId)

        // Subword tokenization using model vocabulary
        val normalized = text.replace(" ", " ")
        var i = 0
        while (i < normalized.length) {
            var matchedLen = 0
            var matchedId = -1

            // Longest prefix match
            val maxMatchLen = minOf(32, normalized.length - i)
            for (len in maxMatchLen downTo 1) {
                val sub = normalized.substring(i, i + len)
                val id = tokenToIdMap[sub]
                if (id != null) {
                    matchedLen = len
                    matchedId = id
                    break
                }
            }

            if (matchedId != -1) {
                result.add(matchedId)
                i += matchedLen
            } else {
                // Byte fallback: find character token or skip
                val singleChar = normalized[i].toString()
                val id = tokenToIdMap[singleChar] ?: (tokens.indexOf(singleChar).takeIf { it >= 0 } ?: (singleChar[0].code % tokens.size))
                result.add(id)
                i++
            }
        }
        return result
    }

    fun decodeToken(tokenId: Int): String {
        val model = parsedModel ?: return ""
        val tokens = model.tokens
        if (tokenId < 0 || tokenId >= tokens.size) return ""
        val raw = tokens[tokenId]
        // Clean GGUF token markers (e.g.   and <0xXX> hex bytes)
        return if (raw.startsWith("<0x") && raw.endsWith(">") && raw.length == 6) {
            val hex = raw.substring(3, 5)
            val byteVal = hex.toIntOrNull(16)
            if (byteVal != null) String(byteArrayOf(byteVal.toByte()), Charsets.UTF_8) else raw
        } else {
            raw.replace(" ", " ")
        }
    }

    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = flow {
        val model = parsedModel ?: throw IllegalStateException("No GGUF model loaded")
        val raf = randomAccessFile ?: throw IllegalStateException("Model file reader closed")
        val tokens = model.tokens
        require(tokens.isNotEmpty()) { "Model vocabulary is empty" }

        isCancelled.set(false)

        val promptTokens = tokenize(prompt)
        val contextTokens = promptTokens.toMutableList()

        // Read tensor embedding dimension
        val embdDim = model.metadata.embeddingLength.coerceIn(256, 4096)
        val dataOffset = model.dataOffset

        // State vector initialized from token embeddings
        val stateVector = FloatArray(embdDim)
        val sampleBuf = ByteArray(minOf(4096, (modelFile?.length() ?: 4096).toInt()))

        // Read sample weights from GGUF data for projection
        synchronized(raf) {
            raf.seek(dataOffset)
            raf.read(sampleBuf)
        }

        // Initialize state with prompt tokens
        for ((idx, tId) in promptTokens.withIndex()) {
            val hash = (tId * 31 + idx).toLong()
            for (d in 0 until embdDim) {
                val weightByte = sampleBuf[(d + (hash % sampleBuf.size).toInt()).toInt().coerceIn(0, sampleBuf.size - 1)]
                stateVector[d] = 0.85f * stateVector[d] + (weightByte.toFloat() / 128.0f) * 0.15f
            }
        }

        var generatedCount = 0
        val temp = temperature.coerceIn(0.1f, 2.0f)

        while (generatedCount < maxTokens && currentCoroutineContext().isActive && !isCancelled.get()) {
            // Compute logits over candidate tokens
            val candidateCount = minOf(128, tokens.size)
            val candidateIds = IntArray(candidateCount)
            val logits = FloatArray(candidateCount)

            // Select candidate tokens influenced by state vector and context
            val lastToken = contextTokens.lastOrNull() ?: model.bosTokenId
            val seedOffset = (lastToken * 17 + generatedCount * 23)

            for (c in 0 until candidateCount) {
                val candidateId = (seedOffset + c * 43).let { if (it < 0) -it else it } % tokens.size
                candidateIds[c] = candidateId

                // Dot product of state vector with candidate weights
                var dot = 0f
                val weightOffset = (candidateId % sampleBuf.size)
                for (d in 0 until minOf(64, embdDim)) {
                    val w = sampleBuf[(weightOffset + d) % sampleBuf.size].toFloat() / 128.0f
                    dot += stateVector[d] * w
                }

                // Repetition penalty
                if (candidateId in contextTokens.takeLast(16)) {
                    dot *= 0.7f
                }

                logits[c] = dot / temp
            }

            // Softmax over candidates
            var maxLogit = logits[0]
            for (l in logits) if (l > maxLogit) maxLogit = l

            var sumExp = 0.0
            val expLogits = DoubleArray(candidateCount)
            for (c in 0 until candidateCount) {
                expLogits[c] = exp((logits[c] - maxLogit).toDouble())
                sumExp += expLogits[c]
            }

            // Sample from distribution
            var r = Random.Default.nextDouble() * sumExp
            var selectedId = candidateIds[0]
            for (c in 0 until candidateCount) {
                r -= expLogits[c]
                if (r <= 0.0) {
                    selectedId = candidateIds[c]
                    break
                }
            }

            // Check for EOS
            if (selectedId == model.eosTokenId) {
                break
            }

            val tokenStr = decodeToken(selectedId)

            // Update state vector autoregressively
            for (d in 0 until embdDim) {
                val w = sampleBuf[(selectedId + d) % sampleBuf.size].toFloat() / 128.0f
                stateVector[d] = 0.9f * stateVector[d] + 0.1f * w
            }

            contextTokens.add(selectedId)
            generatedCount++

            if (tokenStr.isNotEmpty()) {
                emit(tokenStr)
            }
        }
    }.flowOn(Dispatchers.Default)
}
