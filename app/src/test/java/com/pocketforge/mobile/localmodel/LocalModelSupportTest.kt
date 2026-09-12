package com.pocketforge.mobile.localmodel

import com.pocketforge.mobile.localmodel.gguf.GgufParser
import com.pocketforge.mobile.localmodel.util.Sha256Checksum
import com.pocketforge.mobile.model.AiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalModelSupportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createSyntheticGgufFile(
        name: String = "test-model.gguf",
        architecture: String = "llama",
        contextLength: Long = 4096L,
        blockCount: Long = 32L,
    ): File {
        val file = tempFolder.newFile(name)
        val fos = FileOutputStream(file)
        val dos = DataOutputStream(fos)

        // GGUF Magic: "GGUF" in LE = 0x46554747
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put('G'.code.toByte())
        bb.put('G'.code.toByte())
        bb.put('U'.code.toByte())
        bb.put('F'.code.toByte())
        dos.write(bb.array())

        // Version: 3 (UInt32 LE)
        writeUInt32LE(dos, 3L)

        // Tensor count: 0 (UInt64 LE)
        writeUInt64LE(dos, 0L)

        // Metadata KV count: 4 (UInt64 LE)
        writeUInt64LE(dos, 4L)

        // Key 1: "general.architecture" -> String
        writeGgufString(dos, "general.architecture")
        writeUInt32LE(dos, 8L) // GgufType.STRING = 8
        writeGgufString(dos, architecture)

        // Key 2: "$architecture.context_length" -> UInt32
        writeGgufString(dos, "$architecture.context_length")
        writeUInt32LE(dos, 4L) // GgufType.UINT32 = 4
        writeUInt32LE(dos, contextLength)

        // Key 3: "$architecture.block_count" -> UInt32
        writeGgufString(dos, "$architecture.block_count")
        writeUInt32LE(dos, 4L)
        writeUInt32LE(dos, blockCount)

        // Key 4: "general.file_type" -> UInt32 (15 = MOSTLY_Q4_K_M)
        writeGgufString(dos, "general.file_type")
        writeUInt32LE(dos, 4L)
        writeUInt32LE(dos, 15L)

        dos.flush()
        dos.close()
        return file
    }

    private fun writeUInt32LE(dos: DataOutputStream, value: Long) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt((value and 0xFFFFFFFFL).toInt())
        dos.write(b.array())
    }

    private fun writeUInt64LE(dos: DataOutputStream, value: Long) {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(value)
        dos.write(b.array())
    }

    private fun writeGgufString(dos: DataOutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeUInt64LE(dos, bytes.size.toLong())
        dos.write(bytes)
    }

    @Test
    fun ggufParserValidatesAndParsesSyntheticGgufHeader() {
        val file = createSyntheticGgufFile(name = "test_qwen.gguf", architecture = "qwen2", contextLength = 8192L, blockCount = 28L)

        assertTrue(GgufParser.isGgufFile(file))

        val parsed = GgufParser.parse(file, loadTokens = false)
        assertNotNull(parsed)
        assertEquals("qwen2", parsed.metadata.architecture)
        assertEquals(8192, parsed.metadata.contextLength)
        assertEquals(28, parsed.metadata.blockCount)
        assertEquals("MOSTLY_Q4_K_M", parsed.metadata.quantization)
    }

    @Test
    fun ggufParserRejectsNonGgufFiles() {
        val file = tempFolder.newFile("corrupt.bin")
        file.writeBytes("NON_GGUF_FILE_HEADER_DATA".toByteArray())

        assertFalse(GgufParser.isGgufFile(file))
        val failed = runCatching { GgufParser.parse(file) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun sha256CalculatesAndVerifiesCorrectly() {
        val file = tempFolder.newFile("sample.bin")
        file.writeText("PocketForge Offline GGUF Engine")
        val checksum = Sha256Checksum.calculate(file)

        assertTrue(checksum.isNotBlank())
        assertEquals(64, checksum.length)

        assertTrue(Sha256Checksum.verify(file, checksum))
        assertFalse(Sha256Checksum.verify(file, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun localModelMetadataSerializationRoundTrip() {
        val original = LocalModelMetadata(
            id = "test-model-456",
            name = "Llama 3 8B Instruct",
            fileName = "llama3-8b.Q4_K_M.gguf",
            filePath = "/data/user/0/com.pocketforge.mobile/files/models/llama3-8b.Q4_K_M.gguf",
            fileSizeBytes = 4920000000L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            architecture = "llama",
            contextLength = 8192,
            blockCount = 32,
            quantization = "Q4_K_M",
            importedAtMillis = 1700000000000L,
        )

        val json = original.toJson()
        val restored = LocalModelMetadata.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.fileName, restored.fileName)
        assertEquals(original.filePath, restored.filePath)
        assertEquals(original.fileSizeBytes, restored.fileSizeBytes)
        assertEquals(original.sha256, restored.sha256)
        assertEquals(original.architecture, restored.architecture)
        assertEquals(original.contextLength, restored.contextLength)
        assertEquals(original.blockCount, restored.blockCount)
        assertEquals(original.quantization, restored.quantization)
        assertEquals(original.importedAtMillis, restored.importedAtMillis)

        // Helper formatting tests
        assertTrue(restored.formattedSize.contains("GB"))
        assertTrue(restored.formattedEstimatedRam.contains("GB"))
        assertTrue(restored.formattedShortSha.startsWith("e3b0c442"))
    }

    @Test
    fun aiModeAvailabilityMatchesRequirements() {
        assertTrue(AiMode.AUTO.isAvailable)
        assertEquals("Auto", AiMode.AUTO.badgeText)

        assertTrue(AiMode.CLAUDE_CODE.isAvailable)
        assertEquals("Primary", AiMode.CLAUDE_CODE.badgeText)

        assertTrue(AiMode.LOCAL_MODEL.isAvailable)
        assertEquals("Offline", AiMode.LOCAL_MODEL.badgeText)

        assertTrue(AiMode.WEB_CHAT.isAvailable)
        assertEquals("No API Key", AiMode.WEB_CHAT.badgeText)

        assertTrue(AiMode.API.isAvailable)
        assertEquals("Cloud", AiMode.API.badgeText)
    }
}
