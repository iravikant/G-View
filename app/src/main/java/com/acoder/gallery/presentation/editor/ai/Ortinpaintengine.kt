package com.acoder.gallery.presentation.editor.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.collections.mapOf

/** The model file is not in the app's assets. */
class ModelMissingException(message: String) : Exception(message)

/** The model file exists but cannot be loaded, or does not have the expected inputs / outputs. */
class ModelIncompatibleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * MI-GAN "pipeline" ONNX model (Picsart AI Research, MIT licence), 512 px Places2 variant.
 *
 * Contract, taken from scripts/create_onnx_pipeline.py in the MI-GAN repository:
 *   inputs : image  uint8 [1, 3, H, W]  RGB, planar (CHW), 0..255
 *            mask   uint8 [1, 1, H, W]  strictly binary: 255 = KNOWN pixel (keep), 0 = HOLE (erase)
 *   output : result uint8 [1, 3, H, W]  RGB, same size as the input, known pixels preserved
 * H and W are dynamic. Pre/post-processing (crop around the hole, resize to 512, normalise, blend)
 * is part of the graph.
 *
 * The adapter checks these properties against the loaded model instead of assuming them.
 */
object MiganModel {
    const val ASSET_PATH = "models/migan_pipeline_v2.onnx"
    const val FILE_NAME = "migan_pipeline_v2.onnx"
}

class OrtInpaintEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val imageInput: String,
    private val maskInput: String
) : InpaintEngine, AutoCloseable {

    private var closed = false

    /** Runs one window. Synchronised so a running inference can never race with [close]. */
    @Synchronized
    override fun inpaint(pixels: IntArray, known: BooleanArray, width: Int, height: Int): IntArray {
        check(!closed) { "The inpainting engine has been released" }
        val n = width * height
        require(pixels.size == n && known.size == n) { "window size mismatch" }

        val img = ByteBuffer.allocateDirect(3 * n).order(ByteOrder.nativeOrder())
        val msk = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            val p = pixels[i]
            img.put(i, (p shr 16).toByte())          // R plane
            img.put(n + i, (p shr 8).toByte())       // G plane
            img.put(2 * n + i, p.toByte())           // B plane
            msk.put(i, if (known[i]) 0xFF.toByte() else 0)
        }

        var imgTensor: OnnxTensor? = null
        var mskTensor: OnnxTensor? = null
        try {
            imgTensor = OnnxTensor.createTensor(env, img, longArrayOf(1, 3, height.toLong(), width.toLong()), OnnxJavaType.UINT8)
            mskTensor = OnnxTensor.createTensor(env, msk, longArrayOf(1, 1, height.toLong(), width.toLong()), OnnxJavaType.UINT8)
            return session.run(mapOf(imageInput to imgTensor, maskInput to mskTensor)).use { result ->
                val tensor = result.get(0) as? OnnxTensor
                    ?: throw ModelIncompatibleException("The model returned something other than a tensor")
                val info = tensor.info
                val shape = info.shape
                if (info.type != OnnxJavaType.UINT8 || shape.size != 4 || shape[1] != 3L ||
                    shape[2] != height.toLong() || shape[3] != width.toLong()
                ) {
                    throw ModelIncompatibleException(
                        "Unexpected model output ${info.type} ${shape.contentToString()}, wanted uint8 [1,3,$height,$width]"
                    )
                }
                val out = tensor.byteBuffer
                IntArray(n) { i ->
                    val r = out.get(i).toInt() and 255
                    val g = out.get(n + i).toInt() and 255
                    val b = out.get(2 * n + i).toInt() and 255
                    (pixels[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                }
            }
        } finally {
            imgTensor?.close()
            mskTensor?.close()
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        fun isBundled(context: Context): Boolean = try {
            context.assets.open(MiganModel.ASSET_PATH).close()
            true
        } catch (e: IOException) {
            false
        }

        /** Blocking: copies the asset on first use and creates the session. Call off the main thread. */
        fun load(context: Context): OrtInpaintEngine {
            val file = prepareModelFile(context)
            try {
                val env = OrtEnvironment.getEnvironment()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val session = OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(threads)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    env.createSession(file.absolutePath, opts)
                }
                try {
                    val (imageName, maskName) = validate(session)
                    return OrtInpaintEngine(env, session, imageName, maskName)
                } catch (e: Throwable) {
                    session.close()
                    throw e
                }
            } catch (e: ModelIncompatibleException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                throw ModelIncompatibleException("ONNX Runtime has no native library for this device", e)
            } catch (e: Exception) {
                throw ModelIncompatibleException(e.message ?: "The model could not be loaded", e)
            }
        }

        private fun validate(session: OrtSession): Pair<String, String> {
            val inputs = session.inputInfo
            if (inputs.size != 2) {
                throw ModelIncompatibleException("Expected 2 inputs (image, mask) but the model has ${inputs.size}")
            }
            for ((name, node) in inputs) {
                val info = node.info as? TensorInfo
                    ?: throw ModelIncompatibleException("Input '$name' is not a tensor")
                if (info.type != OnnxJavaType.UINT8 || info.shape.size != 4) {
                    throw ModelIncompatibleException(
                        "Input '$name' is ${info.type} ${info.shape.contentToString()}, wanted uint8 with 4 dimensions"
                    )
                }
            }
            if (session.outputInfo.isEmpty()) throw ModelIncompatibleException("The model has no outputs")

            // Match by channel count: the image has 3 channels, the mask 1. Fall back to names / order.
            val names = inputs.keys.toList()
            val byChannels = names.associateWith { (inputs.getValue(it).info as TensorInfo).shape[1] }
            val image = names.firstOrNull { byChannels[it] == 3L } ?: names.firstOrNull { "image" in it.lowercase() } ?: names[0]
            val mask = names.firstOrNull { it != image } ?: throw ModelIncompatibleException("Could not identify the mask input")
            return image to mask
        }

        private fun prepareModelFile(context: Context): File {
            val app = context.applicationContext
            val dir = File(app.filesDir, "models").apply { mkdirs() }
            val target = File(dir, MiganModel.FILE_NAME)

            val installedAt = try {
                app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
            } catch (e: Exception) {
                Long.MAX_VALUE
            }
            if (target.exists() && target.length() > 0 && target.lastModified() >= installedAt) return target

            val tmp = File(dir, MiganModel.FILE_NAME + ".tmp")
            try {
                app.assets.open(MiganModel.ASSET_PATH).use { input ->
                    tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            } catch (e: IOException) {
                tmp.delete()
                throw ModelMissingException(
                    "Model file not found in assets (expected ${MiganModel.ASSET_PATH})"
                )
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw ModelIncompatibleException("Could not install the model file")
            }
            return target
        }
    }
}

/**
 * Loads the engine once and shares it. Closing waits for any running inference, so call it from a
 * background thread.
 */
class InpaintEngineHolder(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var engine: OrtInpaintEngine? = null

    suspend fun get(): OrtInpaintEngine = mutex.withLock {
        engine ?: withContext(Dispatchers.IO) { OrtInpaintEngine.load(app) }.also { engine = it }
    }

    override fun close() {
        val e = engine
        engine = null
        e?.close()
    }
}