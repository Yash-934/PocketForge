package com.pocketforge.mobile.localmodel.gguf

import com.pocketforge.mobile.localmodel.LocalModelMetadata
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufParsedModel(
    val metadata: LocalModelMetadata,
    val tokens: List<String> = emptyList(),
    val tokenScores: List<Float> = emptyList(),
    val bosTokenId: Int = 1,
    val eosTokenId: Int = 2,
    val tensorMap: Map<String, GgufTensorInfo> = emptyMap(),
    val dataOffset: Long = 0L,
)

data class GgufTensorInfo(
    val name: String,
    val dimensions: LongArray,
    val type: Int,
    val offset: Long,
)

object GgufParser {
    const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

    // GGUF Value Types
    const val TYPE_UINT8 = 0
    const val TYPE_INT8 = 1
    const val TYPE_UINT16 = 2
    const val TYPE_INT16 = 3
    const val TYPE_UINT32 = 4
    const val TYPE_INT32 = 5
    const val TYPE_FLOAT32 = 6
    const val TYPE_BOOL = 7
    const val TYPE_STRING = 8
    const val TYPE_ARRAY = 9
    const val TYPE_UINT64 = 10
    const val TYPE_INT64 = 11
    const val TYPE_FLOAT64 = 12

    fun isGgufFile(file: File): Boolean {
        if (!file.isFile || file.length() < 16) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(4)
                raf.readFully(buf)
                val magic = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
                magic == GGUF_MAGIC
            }
        }.getOrDefault(false)
    }

    fun isGgufStream(stream: InputStream): Boolean {
        return runCatching {
            val buf = ByteArray(4)
            var read = 0
            while (read < 4) {
                val r = stream.read(buf, read, 4 - read)
                if (r < 0) return false
                read += r
            }
            val magic = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
            magic == GGUF_MAGIC
        }.getOrDefault(false)
    }

    fun parse(file: File, loadTokens: Boolean = true): GgufParsedModel {
        require(file.isFile) { "Model file does not exist: ${file.absolutePath}" }
        val raf = RandomAccessFile(file, "r")
        return try {
            parseInternal(raf, file, loadTokens)
        } finally {
            raf.close()
        }
    }

    private fun parseInternal(raf: RandomAccessFile, file: File, loadTokens: Boolean): GgufParsedModel {
        val headerBuf = ByteArray(4)
        raf.readFully(headerBuf)
        val magic = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN).int
        require(magic == GGUF_MAGIC) { "Invalid GGUF magic header: 0x${Integer.toHexString(magic)}" }

        val version = readUint32(raf)
        require(version in 1..3) { "Unsupported GGUF version: $version" }

        val tensorCount = readUint64(raf)
        val kvCount = readUint64(raf)

        val kvMap = mutableMapOf<String, Any>()
        var extractedTokens = emptyList<String>()
        var extractedScores = emptyList<Float>()

        for (i in 0 until kvCount) {
            val key = readString(raf)
            val valType = readUint32(raf).toInt()

            if (key == "tokenizer.ggml.tokens") {
                if (valType == TYPE_ARRAY) {
                    val arrType = readUint32(raf).toInt()
                    val arrLen = readUint64(raf)
                    if (arrType == TYPE_STRING && loadTokens && arrLen <= 300000) {
                        val tokenList = ArrayList<String>(arrLen.toInt())
                        for (k in 0 until arrLen) {
                            tokenList.add(readString(raf))
                        }
                        extractedTokens = tokenList
                        kvMap[key] = tokenList.size
                    } else {
                        // Skip array if not loading
                        skipArray(raf, arrType, arrLen)
                        kvMap[key] = arrLen.toInt()
                    }
                } else {
                    skipValue(raf, valType)
                }
            } else if (key == "tokenizer.ggml.scores") {
                if (valType == TYPE_ARRAY) {
                    val arrType = readUint32(raf).toInt()
                    val arrLen = readUint64(raf)
                    if (arrType == TYPE_FLOAT32 && loadTokens && arrLen <= 300000) {
                        val scoreList = ArrayList<Float>(arrLen.toInt())
                        for (k in 0 until arrLen) {
                            scoreList.add(readFloat32(raf))
                        }
                        extractedScores = scoreList
                        kvMap[key] = scoreList.size
                    } else {
                        skipArray(raf, arrType, arrLen)
                    }
                } else {
                    skipValue(raf, valType)
                }
            } else {
                val value = readValue(raf, valType)
                if (value != null) {
                    kvMap[key] = value
                }
            }
        }

        // Parse tensor infos
        val tensorMap = mutableMapOf<String, GgufTensorInfo>()
        for (i in 0 until tensorCount) {
            val tName = readString(raf)
            val nDims = readUint32(raf).toInt()
            val dims = LongArray(nDims)
            for (d in 0 until nDims) {
                dims[d] = readUint64(raf)
            }
            val tType = readUint32(raf).toInt()
            val offset = readUint64(raf)
            tensorMap[tName] = GgufTensorInfo(tName, dims, tType, offset)
        }

        // Calculate alignment and data offset
        val alignment = (kvMap["general.alignment"] as? Number)?.toLong() ?: 32L
        val currentPos = raf.filePointer
        val dataOffset = if (currentPos % alignment == 0L) currentPos else ((currentPos / alignment) + 1) * alignment

        // Extract model attributes
        val arch = kvMap["general.architecture"]?.toString() ?: "llama"
        val modelName = kvMap["general.name"]?.toString() ?: file.nameWithoutExtension
        val ctxLen = (kvMap["$arch.context_length"] as? Number)?.toInt()
            ?: (kvMap["context_length"] as? Number)?.toInt() ?: 2048
        val embdLen = (kvMap["$arch.embedding_length"] as? Number)?.toInt()
            ?: (kvMap["embedding_length"] as? Number)?.toInt() ?: 2048
        val blockCount = (kvMap["$arch.block_count"] as? Number)?.toInt()
            ?: (kvMap["block_count"] as? Number)?.toInt() ?: 16
        val headCount = (kvMap["$arch.attention.head_count"] as? Number)?.toInt()
            ?: (kvMap["attention.head_count"] as? Number)?.toInt() ?: 16
        val vocabSize = (kvMap["tokenizer.ggml.tokens"] as? Number)?.toInt()
            ?: extractedTokens.size.takeIf { it > 0 } ?: 32000
        val bosId = (kvMap["tokenizer.ggml.bos_token_id"] as? Number)?.toInt() ?: 1
        val eosId = (kvMap["tokenizer.ggml.eos_token_id"] as? Number)?.toInt() ?: 2

        val fileTypeNum = (kvMap["general.file_type"] as? Number)?.toInt()
        val quantization = fileTypeToQuantizationName(fileTypeNum, tensorMap)

        val metadata = LocalModelMetadata(
            id = java.util.UUID.randomUUID().toString(),
            name = modelName,
            fileName = file.name,
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            sha256 = "", // Will be filled by caller / store
            architecture = arch,
            quantization = quantization,
            contextLength = ctxLen,
            embeddingLength = embdLen,
            blockCount = blockCount,
            headCount = headCount,
            vocabSize = vocabSize,
            tensorCount = tensorCount,
            importedAtMillis = System.currentTimeMillis(),
        )

        return GgufParsedModel(
            metadata = metadata,
            tokens = extractedTokens,
            tokenScores = extractedScores,
            bosTokenId = bosId,
            eosTokenId = eosId,
            tensorMap = tensorMap,
            dataOffset = dataOffset,
        )
    }

    private fun fileTypeToQuantizationName(fileType: Int?, tensorMap: Map<String, GgufTensorInfo>): String {
        return when (fileType) {
            0 -> "ALL_F32"
            1 -> "MOSTLY_F16"
            2 -> "MOSTLY_Q4_0"
            3 -> "MOSTLY_Q4_1"
            7 -> "MOSTLY_Q8_0"
            8 -> "MOSTLY_Q5_0"
            9 -> "MOSTLY_Q5_1"
            10 -> "MOSTLY_Q2_K"
            11 -> "MOSTLY_Q3_K_S"
            12 -> "MOSTLY_Q3_K_M"
            13 -> "MOSTLY_Q3_K_L"
            14 -> "MOSTLY_Q4_K_S"
            15 -> "MOSTLY_Q4_K_M"
            16 -> "MOSTLY_Q5_K_S"
            17 -> "MOSTLY_Q5_K_M"
            18 -> "MOSTLY_Q6_K"
            else -> {
                // Infer from tensor types
                val sampleTensor = tensorMap.values.firstOrNull { it.name.contains("weight") }
                when (sampleTensor?.type) {
                    0 -> "F32"
                    1 -> "F16"
                    2 -> "Q4_0"
                    8 -> "Q8_0"
                    12 -> "Q4_K_M"
                    15 -> "Q4_K_M"
                    17 -> "Q5_K_M"
                    18 -> "Q6_K"
                    else -> "Quantized (GGUF)"
                }
            }
        }
    }

    private fun readUint32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    private fun readUint64(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readFloat32(raf: RandomAccessFile): Float {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun readString(raf: RandomAccessFile): String {
        val len = readUint64(raf)
        if (len <= 0) return ""
        if (len > 16 * 1024 * 1024) throw IllegalStateException("String length too large: $len")
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(raf: RandomAccessFile, type: Int): Any? {
        return when (type) {
            TYPE_UINT8, TYPE_INT8 -> raf.readByte().toInt()
            TYPE_UINT16, TYPE_INT16 -> {
                val b = ByteArray(2)
                raf.readFully(b)
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            }
            TYPE_UINT32, TYPE_INT32 -> readUint32(raf).toInt()
            TYPE_FLOAT32 -> readFloat32(raf)
            TYPE_BOOL -> raf.readByte() != 0.toByte()
            TYPE_STRING -> readString(raf)
            TYPE_UINT64, TYPE_INT64 -> readUint64(raf)
            TYPE_FLOAT64 -> {
                val b = ByteArray(8)
                raf.readFully(b)
                ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).double
            }
            TYPE_ARRAY -> {
                val itemType = readUint32(raf).toInt()
                val count = readUint64(raf)
                skipArray(raf, itemType, count)
                null
            }
            else -> null
        }
    }

    private fun skipValue(raf: RandomAccessFile, type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> raf.skipBytes(1)
            TYPE_UINT16, TYPE_INT16 -> raf.skipBytes(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> raf.skipBytes(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> raf.skipBytes(8)
            TYPE_STRING -> {
                val len = readUint64(raf)
                raf.skipBytes(len.toInt())
            }
            TYPE_ARRAY -> {
                val itemType = readUint32(raf).toInt()
                val count = readUint64(raf)
                skipArray(raf, itemType, count)
            }
        }
    }

    private fun skipArray(raf: RandomAccessFile, itemType: Int, count: Long) {
        val itemSize = when (itemType) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
            TYPE_UINT16, TYPE_INT16 -> 2
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
            else -> -1
        }
        if (itemSize > 0) {
            raf.seek(raf.filePointer + (itemSize * count))
        } else {
            for (i in 0 until count) {
                skipValue(raf, itemType)
            }
        }
    }
}
