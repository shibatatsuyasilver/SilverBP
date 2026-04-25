package com.silverbp.cli

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.silverbp.android.recognition.BpExtractionError
import com.silverbp.android.recognition.BpPrompt
import com.silverbp.android.recognition.BpResponseParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

/**
 * Mac/Linux/Windows CLI to test the SilverBP Gemma 4 OCR pipeline without a phone.
 *
 * Usage:
 *   ./gradlew :cli:run --args="--model /path/to/gemma-4-E4B-it.litertlm --image /path/to/bp.jpg"
 *
 * Reuses BpPrompt + BpResponseParser from the Android app (via srcDirs) so the
 * exact same prompt and parsing logic exercised on-device is what runs here.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val parsed = parseArgs(args)
    val modelFile = File(parsed.modelPath)
    val imageFile = File(parsed.imagePath)

    require(modelFile.exists()) { "Model file not found: ${modelFile.absolutePath}" }
    require(imageFile.exists()) { "Image file not found: ${imageFile.absolutePath}" }

    println("== SilverBP CLI ==")
    println("Model:    ${modelFile.absolutePath}  (${modelFile.length() / 1024 / 1024} MB)")
    println("Image:    ${imageFile.absolutePath}")
    println("Backend:  ${parsed.backend}")
    println()

    val backend = when (parsed.backend.lowercase()) {
        "gpu" -> Backend.GPU()
        else -> Backend.CPU()
    }

    val cfg = EngineConfig(
        modelPath = modelFile.absolutePath,
        backend = backend,
        visionBackend = backend,
    )

    print("Initialising engine… ")
    val initMs = measureTimeMillis {
        Engine(cfg).also { it.initialize() }.use { engine ->
            println("done.")

            print("Running inference… ")
            var raw = ""
            val inferMs = measureTimeMillis {
                engine.createConversation().use { conversation ->
                    val response = conversation.sendMessage(
                        Contents.of(
                            Content.ImageFile(imageFile.absolutePath),
                            Content.Text(BpPrompt.systemAndExtract()),
                        )
                    )
                    raw = response.toString()
                }
            }
            println("done in ${inferMs} ms.")
            println()
            println("--- Raw model output ---")
            println(raw)
            println("------------------------")
            println()

            try {
                val reading = BpResponseParser.parse(raw)
                println("Parsed reading:")
                println("  Systolic:           ${reading.systolic}")
                println("  Diastolic:          ${reading.diastolic}")
                println("  Pulse:              ${reading.pulse ?: "(null)"}")
                println("  Irregular HB:       ${reading.irregularHeartbeat ?: "(null)"}")
                println("  Confidence:         ${reading.confidence ?: "(null)"}")
                if (reading.confidence != null) {
                    val pct = (reading.confidence!! * 100).toInt()
                    println("  Confidence pct:     $pct%")
                }
            } catch (e: BpExtractionError) {
                System.err.println("Parse error: ${e.message}")
                exitProcess(2)
            }
        }
    }
    println()
    println("Total wall time: ${initMs} ms.")
}

private data class CliArgs(val modelPath: String, val imagePath: String, val backend: String)

private fun parseArgs(args: Array<String>): CliArgs {
    var model: String? = null
    var image: String? = null
    var backend = "cpu"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--model", "-m" -> { model = args[++i] }
            "--image", "-i" -> { image = args[++i] }
            "--backend", "-b" -> { backend = args[++i] }
            "--help", "-h" -> { printUsage(); exitProcess(0) }
            else -> { System.err.println("Unknown arg: ${args[i]}"); printUsage(); exitProcess(1) }
        }
        i++
    }
    if (model == null || image == null) { printUsage(); exitProcess(1) }
    return CliArgs(model, image, backend)
}

private fun printUsage() {
    System.err.println(
        """
        SilverBP CLI — test Gemma 4 BP-monitor OCR on the desktop.

        Usage:
          --model, -m   <path>   Path to gemma-4-E4B-it.litertlm
          --image, -i   <path>   Path to a JPEG/PNG photo of a BP monitor
          --backend, -b <cpu|gpu>  Default: cpu
          --help, -h             Show this message
        """.trimIndent()
    )
}
