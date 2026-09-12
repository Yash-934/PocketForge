package com.pocketforge.mobile.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    private val pid: Int?,
    private val fallbackProcess: Process?,
    internal val outputFile: File,
    private val stdin: OutputStream,
) : Process() {
    @Volatile private var result: Int? = null

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = FileInputStream(outputFile)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        result?.let { return it }
        return if (pid != null && NativeSpawn.isAvailable) {
            NativeSpawn.waitFor(pid, false).also { result = it }
        } else {
            (fallbackProcess?.waitFor() ?: 0).also { result = it }
        }
    }

    override fun exitValue(): Int {
        result?.let { return it }
        if (pid != null && NativeSpawn.isAvailable) {
            val status = NativeSpawn.waitFor(pid, true)
            if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
            return status.also { result = it }
        } else {
            return (fallbackProcess?.exitValue() ?: 0).also { result = it }
        }
    }

    override fun destroy() {
        if (pid != null && NativeSpawn.isAvailable) {
            NativeSpawn.kill(pid, 15)
        } else {
            fallbackProcess?.destroy()
        }
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        if (pid != null && NativeSpawn.isAvailable) {
            NativeSpawn.kill(pid, 2)
        } else {
            fallbackProcess?.destroy()
        }
    }

    override fun destroyForcibly(): Process {
        if (pid != null && NativeSpawn.isAvailable) {
            NativeSpawn.kill(pid, 9)
        } else {
            fallbackProcess?.destroyForcibly()
        }
        return this
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        fun start(argv: List<String>, environment: Map<String, String>, cwd: String, outputFile: File): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (NativeSpawn.isAvailable) {
                val spawned = runCatching {
                    NativeSpawn.spawn(
                        argv.toTypedArray(),
                        environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                        cwd,
                        outputFile.absolutePath,
                    )
                }.getOrNull()
                if (spawned != null && spawned.size == 2 && spawned[0] > 0) {
                    val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
                    return NativeSpawnProcess(spawned[0], null, outputFile, input)
                }
            }

            val pb = ProcessBuilder(argv)
            val workDir = File(cwd)
            if (workDir.isDirectory) {
                pb.directory(workDir)
            }
            pb.environment().putAll(environment)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(outputFile))
            val fallback = pb.start()
            return NativeSpawnProcess(null, fallback, outputFile, fallback.outputStream)
        }
    }
}

private object NativeSpawn {
    const val STILL_RUNNING = -2

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("pocketspawn")
        true
    }.getOrDefault(false)

    external fun spawn(argv: Array<String>, environment: Array<String>, cwd: String, outputFile: String): IntArray
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
}
