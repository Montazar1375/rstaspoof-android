package com.rstaspoof.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object BinaryExtractor {
    private const val ASSET_NAME = "rstaspoof"
    private const val JNI_LIB_NAME = "librstaspoof.so"
    private const val CACHE_BINARY_NAME = "rstaspoof"
    private const val LINKER = "/system/bin/linker64"
    private const val PREFS = "binary_extractor"
    private const val VERSION_KEY = "binary_version"

    fun commandLine(context: Context, vararg args: String): List<String> {
        val binary = resolveBinary(context)
        val linker = File(LINKER)
        return if (linker.exists() && linker.canExecute()) {
            listOf(linker.absolutePath, binary.absolutePath) + args.toList()
        } else {
            listOf(binary.absolutePath) + args.toList()
        }
    }

    fun resolveBinary(context: Context): File {
        resolveFromNativeLibDir(context)?.let { return it }
        return resolveFromCodeCache(context)
    }

    /** Installed from jniLibs when AGP packages and extracts the library. */
    private fun resolveFromNativeLibDir(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val candidate = File(nativeDir, JNI_LIB_NAME)
        return candidate.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * Fallback: copy from assets into codeCacheDir (noexec does not apply to code cache).
     * Required when the fake .so is stripped from the APK or not extracted to nativeLibraryDir.
     */
    private fun resolveFromCodeCache(context: Context): File {
        val binary = File(context.codeCacheDir, CACHE_BINARY_NAME)
        val versionCode = currentVersionCode(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt(VERSION_KEY, -1)

        if (!binary.isFile || binary.length() == 0L || installedVersion != versionCode) {
            extractFromAssets(context, binary)
            prefs.edit().putInt(VERSION_KEY, versionCode).apply()
        }
        return binary
    }

    private fun extractFromAssets(context: Context, dest: File) {
        val input = try {
            context.assets.open(ASSET_NAME)
        } catch (e: Exception) {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val listed = nativeDir?.let { dir ->
                File(dir).list()?.joinToString() ?: "(empty)"
            } ?: "null"
            throw IllegalStateException(
                "rstaspoof missing. ABI=${supportedAbis()}, nativeLibraryDir=$nativeDir " +
                    "contents=[$listed]. Run ./scripts/build-android.sh then reinstall.",
                e,
            )
        }
        dest.outputStream().use { out ->
            input.use { it.copyTo(out) }
        }
        dest.setExecutable(true, false)
    }

    private fun currentVersionCode(context: Context): Int = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode
    } catch (_: PackageManager.NameNotFoundException) {
        0
    }

    private fun supportedAbis(): String =
        Build.SUPPORTED_ABIS?.joinToString() ?: Build.CPU_ABI ?: "unknown"
}
