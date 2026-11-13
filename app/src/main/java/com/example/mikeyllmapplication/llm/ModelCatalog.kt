package com.example.mikeyllmapplication.llm

/** A LiteRT-LM model the app knows how to download and run. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val description: String,
    /** KV-cache size (prompt + answer tokens) the engine is created with. */
    val maxTokens: Int,
    /** Rough character budget for the injected device data, leaving room for history and the answer. */
    val contextCharBudget: Int,
    val minRamGb: Int,
)

object ModelCatalog {

    private const val HF = "https://huggingface.co/litert-community"

    val models: List<ModelSpec> = listOf(
        ModelSpec(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B (recommended)",
            fileName = "gemma-4-E2B-it.litertlm",
            url = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeBytes = 2_588_147_712,
            description = "Google's on-device Gemma 4 (effective 2B params). Best answer quality; " +
                "needs ~4 GB free RAM. Runs on CPU in the emulator, GPU on real phones.",
            maxTokens = 8192,
            contextCharBudget = 9000,
            minRamGb = 6,
        ),
        ModelSpec(
            id = "gemma-3-1b",
            displayName = "Gemma 3 1B (int4)",
            fileName = "gemma3-1b-it-int4.litertlm",
            url = "$HF/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
            sizeBytes = 584_417_280,
            description = "Small and fast Gemma. The Hugging Face repo is gated: accept the licence on " +
                "huggingface.co, download it on your computer and push it with adb (see below).",
            maxTokens = 4096,
            contextCharBudget = 5000,
            minRamGb = 3,
        ),
        ModelSpec(
            id = "qwen3-0.6b",
            displayName = "Qwen3 0.6B",
            fileName = "Qwen3-0.6B.litertlm",
            url = "$HF/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            sizeBytes = 613_722_264,
            description = "Very small, quick to download. Good for low-RAM emulators; weaker reasoning.",
            maxTokens = 4096,
            contextCharBudget = 5000,
            minRamGb = 3,
        ),
    )

    val default: ModelSpec get() = models.first()

    fun byId(id: String?): ModelSpec? = models.firstOrNull { it.id == id }
}
