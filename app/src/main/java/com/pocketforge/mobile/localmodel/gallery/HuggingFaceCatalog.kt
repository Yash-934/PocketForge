package com.pocketforge.mobile.localmodel.gallery

import java.net.URI

object HuggingFaceCatalog {

    val curatedModels: List<GalleryModelItem> = listOf(
        // === CODING CATEGORY ===
        GalleryModelItem(
            id = "qwen2.5-coder-1.5b-q4",
            name = "Qwen 2.5 Coder 1.5B Instruct",
            repoId = "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            category = GalleryCategory.CODING,
            sizeBytes = 1_118_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 28,
            architecture = "qwen2",
            recommendedRamBytes = 3_500_000_000L,
            minimumRamBytes = 2_000_000_000L,
            description = "Top-tier compact coding model. Excels at Kotlin, Python, JavaScript, bug diagnosis, and AST refactoring.",
            tags = listOf("coding", "kotlin", "python", "compact", "recommended"),
            isLargeModel = false,
        ),
        GalleryModelItem(
            id = "qwen2.5-coder-7b-q4",
            name = "Qwen 2.5 Coder 7B Instruct",
            repoId = "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
            fileName = "qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
            category = GalleryCategory.CODING,
            sizeBytes = 4_680_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 28,
            architecture = "qwen2",
            recommendedRamBytes = 8_000_000_000L,
            minimumRamBytes = 6_000_000_000L,
            description = "State-of-the-art coding powerhouse. Full codebase comprehension, multi-file code generation, and test creation.",
            tags = listOf("coding", "heavy", "flagship", "high-accuracy"),
            isLargeModel = true,
        ),
        GalleryModelItem(
            id = "deepseek-coder-1.3b-q4",
            name = "DeepSeek Coder 1.3B Instruct",
            repoId = "TheBloke/deepseek-coder-1.3b-instruct-GGUF",
            fileName = "deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
            category = GalleryCategory.CODING,
            sizeBytes = 874_000_000L,
            quantization = "Q4_K_M",
            contextLength = 16_384,
            blockCount = 24,
            architecture = "llama",
            recommendedRamBytes = 2_500_000_000L,
            minimumRamBytes = 1_800_000_000L,
            description = "Lightweight code completion assistant. Trained on 2T code tokens with project-level window context.",
            tags = listOf("coding", "lightweight", "fast"),
            isLargeModel = false,
        ),

        // === REASONING CATEGORY ===
        GalleryModelItem(
            id = "deepseek-r1-distill-1.5b-q4",
            name = "DeepSeek R1 Distill Qwen 1.5B",
            repoId = "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            category = GalleryCategory.REASONING,
            sizeBytes = 1_120_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 28,
            architecture = "qwen2",
            recommendedRamBytes = 3_500_000_000L,
            minimumRamBytes = 2_000_000_000L,
            description = "Trained with reinforcement learning to generate explicit chain-of-thought `<think>` tags before answering.",
            tags = listOf("reasoning", "chain-of-thought", "math", "logic"),
            isLargeModel = false,
        ),
        GalleryModelItem(
            id = "deepseek-r1-distill-7b-q4",
            name = "DeepSeek R1 Distill Qwen 7B",
            repoId = "unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            category = GalleryCategory.REASONING,
            sizeBytes = 4_680_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 28,
            architecture = "qwen2",
            recommendedRamBytes = 8_000_000_000L,
            minimumRamBytes = 6_000_000_000L,
            description = "High-depth reasoning and complex algorithm problem solver. Produces thorough multi-step logical proofs.",
            tags = listOf("reasoning", "flagship", "heavy", "math"),
            isLargeModel = true,
        ),
        GalleryModelItem(
            id = "llama-3.2-3b-q4",
            name = "Llama 3.2 3B Instruct",
            repoId = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            category = GalleryCategory.REASONING,
            sizeBytes = 2_020_000_000L,
            quantization = "Q4_K_M",
            contextLength = 131_072,
            blockCount = 28,
            architecture = "llama",
            recommendedRamBytes = 4_500_000_000L,
            minimumRamBytes = 3_000_000_000L,
            description = "Meta's flagship edge model with 128k context window. Balanced reasoning, summarization, and multilingual logic.",
            tags = listOf("reasoning", "long-context", "meta", "balanced"),
            isLargeModel = true,
        ),

        // === FAST CATEGORY ===
        GalleryModelItem(
            id = "llama-3.2-1b-q4",
            name = "Llama 3.2 1B Instruct",
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            category = GalleryCategory.FAST,
            sizeBytes = 780_000_000L,
            quantization = "Q4_K_M",
            contextLength = 131_072,
            blockCount = 16,
            architecture = "llama",
            recommendedRamBytes = 2_000_000_000L,
            minimumRamBytes = 1_400_000_000L,
            description = "Blazing fast edge inference. Low latency with high responsiveness on low-to-mid range Android hardware.",
            tags = listOf("fast", "ultra-light", "mobile", "low-memory"),
            isLargeModel = false,
        ),
        GalleryModelItem(
            id = "smollm2-1.7b-q4",
            name = "SmolLM2 1.7B Instruct",
            repoId = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            category = GalleryCategory.FAST,
            sizeBytes = 1_060_000_000L,
            quantization = "Q4_K_M",
            contextLength = 8_192,
            blockCount = 24,
            architecture = "llama",
            recommendedRamBytes = 2_500_000_000L,
            minimumRamBytes = 1_800_000_000L,
            description = "Hugging Face official compact architecture trained on curated FineWeb-Edu. Quick generation and compact size.",
            tags = listOf("fast", "huggingface", "compact"),
            isLargeModel = false,
        ),
        GalleryModelItem(
            id = "gemma-2-2b-q4",
            name = "Gemma 2 2B IT",
            repoId = "bartowski/gemma-2-2b-it-GGUF",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            category = GalleryCategory.FAST,
            sizeBytes = 1_630_000_000L,
            quantization = "Q4_K_M",
            contextLength = 8_192,
            blockCount = 26,
            architecture = "gemma2",
            recommendedRamBytes = 3_500_000_000L,
            minimumRamBytes = 2_500_000_000L,
            description = "Google DeepMind's compact edge architecture with sliding window attention for quick conversational replies.",
            tags = listOf("fast", "google", "gemma"),
            isLargeModel = false,
        ),

        // === GENERAL CATEGORY ===
        GalleryModelItem(
            id = "phi-3.5-mini-q4",
            name = "Phi-3.5 Mini 3.8B Instruct",
            repoId = "bartowski/Phi-3.5-mini-instruct-GGUF",
            fileName = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            category = GalleryCategory.GENERAL,
            sizeBytes = 2_390_000_000L,
            quantization = "Q4_K_M",
            contextLength = 131_072,
            blockCount = 32,
            architecture = "phi3",
            recommendedRamBytes = 5_000_000_000L,
            minimumRamBytes = 3_500_000_000L,
            description = "Microsoft's high-efficiency 3.8B model with 128k context, strong synthetic benchmark scores, and multi-turn dialogue.",
            tags = listOf("general", "microsoft", "128k", "versatile"),
            isLargeModel = true,
        ),
        GalleryModelItem(
            id = "qwen2.5-3b-q4",
            name = "Qwen 2.5 3B Instruct",
            repoId = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            category = GalleryCategory.GENERAL,
            sizeBytes = 2_050_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 36,
            architecture = "qwen2",
            recommendedRamBytes = 4_500_000_000L,
            minimumRamBytes = 3_000_000_000L,
            description = "Balanced 3B model offering a sweet spot between capability, multi-lingual fluency, and memory footprint.",
            tags = listOf("general", "qwen", "balanced"),
            isLargeModel = true,
        ),
        GalleryModelItem(
            id = "mistral-7b-v0.3-q4",
            name = "Mistral 7B Instruct v0.3",
            repoId = "bartowski/Mistral-7B-Instruct-v0.3-GGUF",
            fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            category = GalleryCategory.GENERAL,
            sizeBytes = 4_370_000_000L,
            quantization = "Q4_K_M",
            contextLength = 32_768,
            blockCount = 32,
            architecture = "llama",
            recommendedRamBytes = 8_000_000_000L,
            minimumRamBytes = 6_000_000_000L,
            description = "The industry standard 7B open-weights model with function calling capabilities and exceptional general writing.",
            tags = listOf("general", "flagship", "heavy", "mistral"),
            isLargeModel = true,
        ),
    )

    /**
     * Parses a user-entered Hugging Face URL or Repo/File string into a GalleryModelItem.
     * Supports:
     * - https://huggingface.co/{user}/{repo}/resolve/main/{filename}.gguf
     * - https://huggingface.co/{user}/{repo}/blob/main/{filename}.gguf
     * - {user}/{repo}/{filename}.gguf
     */
    fun createCustomModel(inputUrlOrPath: String, category: GalleryCategory = GalleryCategory.GENERAL): Result<GalleryModelItem> {
        val trimmed = inputUrlOrPath.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("URL or model path cannot be empty"))
        }

        val directUrl: String
        val repoId: String
        val fileName: String

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Invalid URL syntax"))
            val path = uri.path ?: ""
            val segments = path.split("/").filter { it.isNotBlank() }
            if (segments.size < 3) {
                return Result.failure(IllegalArgumentException("Hugging Face URL must point to a specific repository and file"))
            }

            repoId = "${segments[0]}/${segments[1]}"
            fileName = segments.last()
            if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("Target file must have a .gguf extension"))
            }

            directUrl = if (path.contains("/blob/")) {
                trimmed.replace("/blob/", "/resolve/")
            } else if (!path.contains("/resolve/")) {
                "https://huggingface.co/$repoId/resolve/main/$fileName"
            } else {
                trimmed
            }
        } else {
            val parts = trimmed.split("/").filter { it.isNotBlank() }
            if (parts.size < 3) {
                return Result.failure(IllegalArgumentException("Enter format: <user>/<repo>/<model>.gguf"))
            }
            repoId = "${parts[0]}/${parts[1]}"
            fileName = parts.last()
            if (!fileName.endsWith(".gguf", ignoreCase = true)) {
                return Result.failure(IllegalArgumentException("Target file must end with .gguf"))
            }
            directUrl = "https://huggingface.co/$repoId/resolve/main/$fileName"
        }

        val name = fileName.removeSuffix(".gguf").replace("-", " ").replace("_", " ")

        val item = GalleryModelItem(
            id = "custom_${System.currentTimeMillis()}",
            name = name,
            repoId = repoId,
            fileName = fileName,
            downloadUrl = directUrl,
            category = category,
            sizeBytes = 2_000_000_000L, // Estimated default
            quantization = "Custom GGUF",
            contextLength = 8_192,
            blockCount = 32,
            architecture = "custom",
            recommendedRamBytes = 4_000_000_000L,
            minimumRamBytes = 2_500_000_000L,
            description = "Custom GGUF imported directly from Hugging Face: $repoId",
            tags = listOf("custom", "huggingface"),
            isLargeModel = true,
        )

        return Result.success(item)
    }
}
