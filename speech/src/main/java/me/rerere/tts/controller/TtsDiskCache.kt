package me.rerere.tts.controller

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.provider.TTSProviderSetting
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "TtsDiskCache"
private const val MANIFEST_FILENAME = "manifest.json"

@Serializable
data class TtsSessionManifest(
    val sessionId: String,
    val createdAt: Long,
    val providerFingerprint: String,
    val originalText: String,
    val chunkCount: Int,
    val chunkTexts: List<String>,
)

class TtsDiskCache(context: Context) {

    private val rootDir: File = File(context.filesDir, "tts").apply { mkdirs() }

    init {
        migrateFromOldCache(context)
    }

    private fun migrateFromOldCache(context: Context) {
        val oldRoot = File(context.cacheDir, "disk_cache/tts")
        if (!oldRoot.exists()) return
        val oldDirs = oldRoot.listFiles { it.isDirectory }
        if (oldDirs.isNullOrEmpty()) {
            oldRoot.delete()
            return
        }
        // Migrate sessions from old cache to new persistent location
        for (oldSession in oldDirs) {
            val newSession = File(rootDir, oldSession.name)
            if (!newSession.exists()) {
                oldSession.renameTo(newSession)
            }
        }
        // Clean up old directory
        oldRoot.delete()
        oldRoot.parentFile?.delete() // also clean up disk_cache/
        Log.i(TAG, "migrateFromOldCache: Migrated TTS sessions from cacheDir to filesDir")
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun sessionDir(sessionId: UUID): File = File(rootDir, sessionId.toString())

    suspend fun writeManifest(manifest: TtsSessionManifest) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = sessionDir(UUID.fromString(manifest.sessionId))
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$MANIFEST_FILENAME.tmp")
            tmp.writeText(json.encodeToString(TtsSessionManifest.serializer(), manifest))
            if (!tmp.renameTo(File(dir, MANIFEST_FILENAME))) {
                // rename can fail on some filesystems; fall back to copy
                File(dir, MANIFEST_FILENAME).writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "writeManifest failed", it) }
    }

    suspend fun readManifest(sessionId: UUID): TtsSessionManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(sessionDir(sessionId), MANIFEST_FILENAME)
            if (!file.exists()) return@runCatching null
            json.decodeFromString(TtsSessionManifest.serializer(), file.readText())
        }.getOrElse {
            Log.w(TAG, "readManifest failed for $sessionId", it)
            null
        }
    }

    suspend fun writeChunk(sessionId: UUID, index: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = sessionDir(sessionId)
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, "$index.mp3")
            val tmp = File(dir, "$index.mp3.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "writeChunk failed for $sessionId/$index", it) }
    }

    suspend fun readChunk(sessionId: UUID, index: Int): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(sessionDir(sessionId), "$index.mp3")
            if (!file.exists()) null else file.readBytes()
        }.getOrElse {
            Log.w(TAG, "readChunk failed for $sessionId/$index", it)
            null
        }
    }

    suspend fun latestSession(): TtsSessionManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val dirs = rootDir.listFiles { f -> f.isDirectory } ?: return@runCatching null
            val sorted = dirs
                .filter { File(it, MANIFEST_FILENAME).exists() }
                .sortedByDescending { it.lastModified() }
            for (dir in sorted) {
                val sessionId = runCatching { UUID.fromString(dir.name) }.getOrNull() ?: continue
                val manifest = readManifest(sessionId) ?: continue
                if (manifest.chunkCount == manifest.chunkTexts.size) return@runCatching manifest
            }
            null
        }.getOrElse {
            Log.w(TAG, "latestSession failed", it)
            null
        }
    }

    /**
     * Find the most recently modified on-disk session whose manifest matches [text]
     * and [fingerprint]. Used by speak() to adopt an existing sessionId when the user
     * starts a fresh speak on the same message that was previously cached, so that
     * replay works after an app restart.
     */
    suspend fun findSessionByText(
        text: String,
        fingerprint: String,
    ): TtsSessionManifest? = withContext(Dispatchers.IO) {
        findSessionByTextBlocking(text, fingerprint)
    }

    /**
     * Synchronous variant used inside speak() on the calling thread. Cheap enough to
     * call inline: a single directory scan + at most one small JSON read per session.
     */
    fun findSessionByTextBlocking(
        text: String,
        fingerprint: String,
    ): TtsSessionManifest? = runCatching {
        val dirs = rootDir.listFiles { f -> f.isDirectory } ?: return@runCatching null
        dirs
            .filter { File(it, MANIFEST_FILENAME).exists() }
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { dir ->
                val sessionId = runCatching { UUID.fromString(dir.name) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val manifest = readManifestBlocking(sessionId) ?: return@firstNotNullOfOrNull null
                if (manifest.chunkCount != manifest.chunkTexts.size) return@firstNotNullOfOrNull null
                if (manifest.providerFingerprint != fingerprint) return@firstNotNullOfOrNull null
                if (manifest.originalText != text) return@firstNotNullOfOrNull null
                manifest
            }
    }.getOrElse {
        Log.w(TAG, "findSessionByTextBlocking failed", it)
        null
    }

    private fun readManifestBlocking(sessionId: UUID): TtsSessionManifest? = runCatching {
        val file = File(sessionDir(sessionId), MANIFEST_FILENAME)
        if (!file.exists()) return@runCatching null
        json.decodeFromString(TtsSessionManifest.serializer(), file.readText())
    }.getOrNull()

    suspend fun deleteSession(sessionId: UUID) = withContext(Dispatchers.IO) {
        sessionDir(sessionId).deleteRecursively()
    }

    suspend fun deleteChunk(sessionId: UUID, index: Int) = withContext(Dispatchers.IO) {
        File(sessionDir(sessionId), "$index.mp3").delete()
    }

    fun fingerprint(provider: TTSProviderSetting): String {
        val raw = when (provider) {
            is TTSProviderSetting.OpenAI -> "${provider.model}|${provider.voice}|${provider.baseUrl}"
            is TTSProviderSetting.Gemini -> "${provider.model}|${provider.voiceName}|${provider.baseUrl}"
            is TTSProviderSetting.SystemTTS -> "system|${provider.speechRate}|${provider.pitch}"
            is TTSProviderSetting.MiniMax -> "${provider.model}|${provider.voiceId}|${provider.baseUrl}|${provider.speed}"
            is TTSProviderSetting.Qwen -> "${provider.model}|${provider.voice}|${provider.baseUrl}"
            is TTSProviderSetting.Groq -> "${provider.model}|${provider.voice}|${provider.baseUrl}"
            is TTSProviderSetting.XAI -> "${provider.voiceId}|${provider.baseUrl}|${provider.language}"
            is TTSProviderSetting.MiMo -> "${provider.model}|${provider.voice}|${provider.baseUrl}"
            is TTSProviderSetting.ElevenLabs -> "${provider.model}|${provider.voiceId}|${provider.baseUrl}"
            is TTSProviderSetting.Step -> "${provider.model}|${provider.voice}|${provider.baseUrl}|${provider.speed}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}