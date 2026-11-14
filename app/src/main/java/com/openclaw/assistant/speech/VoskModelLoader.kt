package com.openclaw.assistant.speech

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.openclaw.assistant.service.HotwordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream

/**
 * Thread-safe shared loader for the bundled Vosk offline model (`assets/model`).
 * Reuses the same `filesDir/model` directory and version marker as [HotwordService].
 */
object VoskModelLoader {
    private const val TAG = "VoskModelLoader"
    private val loadMutex = Mutex()
    @Volatile
    private var cachedModel: Model? = null

    suspend fun getOrLoadModel(context: Context): Model? {
        cachedModel?.let { return it }
        return loadMutex.withLock {
            cachedModel?.let { return it }
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val modelPath = ensureModelUnpacked(appContext) ?: return@withContext null
                runCatching { Model(modelPath) }
                    .onSuccess { cachedModel = it }
                    .onFailure { Log.e(TAG, "Failed to load Vosk model from $modelPath", it) }
                    .getOrNull()
            }
        }
    }

    private fun ensureModelUnpacked(context: Context): String? {
        val targetDir = File(context.filesDir, "model")
        val currentVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (_: Exception) {
            1
        }

        val prefs = context.getSharedPreferences("hotword_prefs", Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt("model_version", 0)
        val dirValid = targetDir.exists() && (targetDir.list()?.isNotEmpty() == true)

        if (!HotwordService.shouldCopyModel(currentVersion, savedVersion, targetDir.exists(), dirValid)) {
            return targetDir.absolutePath
        }

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        val ok = copyAssetFolder(context.assets, "model", targetDir.absolutePath)
        if (ok) {
            prefs.edit().putInt("model_version", currentVersion).apply()
            return targetDir.absolutePath
        }
        return null
    }

    private fun copyAssetFolder(assets: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            val entries = assets.list(fromAssetPath) ?: return false
            File(toPath).mkdirs()
            var ok = true
            for (entry in entries) {
                val subAsset = "$fromAssetPath/$entry"
                val subTarget = "$toPath/$entry"
                val children = assets.list(subAsset)
                ok = if (!children.isNullOrEmpty()) {
                    ok && copyAssetFolder(assets, subAsset, subTarget)
                } else {
                    ok && copyAssetFile(assets, subAsset, subTarget)
                }
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying Vosk asset folder $fromAssetPath", e)
            false
        }
    }

    private fun copyAssetFile(assets: AssetManager, fromAssetPath: String, toPath: String): Boolean {
        return try {
            assets.open(fromAssetPath).use { input ->
                FileOutputStream(File(toPath)).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying Vosk asset file $fromAssetPath", e)
            false
        }
    }
}
