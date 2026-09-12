package com.pocketforge.mobile.localmodel.inference

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

class LlamaBackend(private val context: Context) {

    private val runningProcess = AtomicReference<Process?>(null)

    fun findLlamaExecutable(): File? {
        val candidates = listOf(
            File(context.applicationInfo.nativeLibraryDir, "libllama.so"),
            File(context.applicationInfo.nativeLibraryDir, "llama-cli"),
            File(context.filesDir, "runtime/ubuntu/usr/local/bin/llama-cli"),
            File(context.filesDir, "runtime/ubuntu/usr/bin/llama-cli"),
            File(context.filesDir, "runtime/ubuntu/opt/llama/llama-cli"),
            File(context.filesDir, "bin/llama-cli"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    val isAvailable: Boolean
        get() = findLlamaExecutable() != null

    fun cancel() {
        runningProcess.getAndSet(null)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
    }

    fun generate(
        modelFile: File,
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = flow {
        val exe = findLlamaExecutable() ?: throw IllegalStateException("llama.cpp executable not found on device")
        val cmd = listOf(
            exe.absolutePath,
            "-m", modelFile.absolutePath,
            "-p", prompt,
            "-n", maxTokens.toString(),
            "--temp", temperature.toString(),
            "--repeat-penalty", "1.1",
            "--no-mmap",
        )

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        runningProcess.set(process)

        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val charBuf = CharArray(32)
            var read = 0
            while (currentCoroutineContext().isActive && reader.read(charBuf).also { read = it } != -1) {
                val chunk = String(charBuf, 0, read)
                emit(chunk)
            }
            process.waitFor()
        } finally {
            runningProcess.set(null)
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)
}
