package com.pocketforge.mobile.localmodel

import com.pocketforge.mobile.localmodel.gallery.GalleryCategory
import com.pocketforge.mobile.localmodel.gallery.HuggingFaceCatalog
import com.pocketforge.mobile.localmodel.gguf.GgufParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalModelGalleryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCuratedCatalogContainsAllRequiredCategories() {
        val models = HuggingFaceCatalog.curatedModels
        assertTrue("Catalog should contain at least 10 curated models", models.size >= 10)

        val codingModels = models.filter { it.category == GalleryCategory.CODING }
        val reasoningModels = models.filter { it.category == GalleryCategory.REASONING }
        val fastModels = models.filter { it.category == GalleryCategory.FAST }
        val generalModels = models.filter { it.category == GalleryCategory.GENERAL }

        assertTrue("Should have coding models", codingModels.isNotEmpty())
        assertTrue("Should have reasoning models", reasoningModels.isNotEmpty())
        assertTrue("Should have fast models", fastModels.isNotEmpty())
        assertTrue("Should have general models", generalModels.isNotEmpty())
    }

    @Test
    fun testModelItemMetadataCompleteness() {
        HuggingFaceCatalog.curatedModels.forEach { item ->
            assertTrue("ID should not be blank for ${item.name}", item.id.isNotBlank())
            assertTrue("Name should not be blank", item.name.isNotBlank())
            assertTrue("RepoId should not be blank", item.repoId.contains("/"))
            assertTrue("File name should end with .gguf", item.fileName.endsWith(".gguf", ignoreCase = true))
            assertTrue("Download URL should start with https://", item.downloadUrl.startsWith("https://"))
            assertTrue("Size should be positive", item.sizeBytes > 0L)
            assertTrue("Quantization should be specified", item.quantization.isNotBlank())
            assertTrue("Context length should be at least 2048", item.contextLength >= 2048)
            assertTrue("Recommended RAM should exceed 1GB", item.recommendedRamBytes >= 1_000_000_000L)
            assertTrue("Description should not be empty", item.description.isNotBlank())

            // Formatted helpers
            assertTrue("Formatted size should not be empty", item.formattedSize.isNotBlank())
            assertTrue("Formatted rec RAM should not be empty", item.formattedRecommendedRam.isNotBlank())
            assertTrue("Formatted min RAM should not be empty", item.formattedMinimumRam.isNotBlank())

            // Verify large model flag consistency
            if (item.sizeBytes >= 2_000_000_000L) {
                assertTrue("Models >= 2GB should be marked as isLargeModel", item.isLargeModel)
            }
        }
    }

    @Test
    fun testCreateCustomModelDirectUrl() {
        val url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        val result = HuggingFaceCatalog.createCustomModel(url, GalleryCategory.CODING)

        assertTrue(result.isSuccess)
        val item = result.getOrNull()
        assertNotNull(item)
        assertEquals("bartowski/Llama-3.2-3B-Instruct-GGUF", item?.repoId)
        assertEquals("Llama-3.2-3B-Instruct-Q4_K_M.gguf", item?.fileName)
        assertEquals(url, item?.downloadUrl)
        assertEquals(GalleryCategory.CODING, item?.category)
    }

    @Test
    fun testCreateCustomModelBlobUrlConversion() {
        val blobUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/blob/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"
        val expectedResolveUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

        val result = HuggingFaceCatalog.createCustomModel(blobUrl)
        assertTrue(result.isSuccess)
        assertEquals(expectedResolveUrl, result.getOrNull()?.downloadUrl)
    }

    @Test
    fun testCreateCustomModelShorthandSyntax() {
        val shorthand = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
        val result = HuggingFaceCatalog.createCustomModel(shorthand)

        assertTrue(result.isSuccess)
        val item = result.getOrNull()
        assertNotNull(item)
        assertEquals("unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", item?.repoId)
        assertEquals("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", item?.fileName)
        assertEquals(
            "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            item?.downloadUrl
        )
    }

    @Test
    fun testCreateCustomModelRejectsInvalidInputs() {
        // Non-gguf file
        val nonGguf = HuggingFaceCatalog.createCustomModel("https://huggingface.co/user/repo/resolve/main/weights.bin")
        assertTrue(nonGguf.isFailure)

        // Empty string
        val emptyInput = HuggingFaceCatalog.createCustomModel("   ")
        assertTrue(emptyInput.isFailure)

        // Incomplete path
        val incomplete = HuggingFaceCatalog.createCustomModel("user/repo")
        assertTrue(incomplete.isFailure)
    }

    @Test
    fun testArbitraryBinarySecurityRejection() {
        // Arbitrary shell script or elf executable disguise
        val fakeModel = tempFolder.newFile("malicious-script.gguf")
        fakeModel.writeBytes("#!/bin/sh\nrm -rf /\n".toByteArray())

        assertFalse("GgufParser must reject non-GGUF magic bytes", GgufParser.isGgufFile(fakeModel))
    }
}
