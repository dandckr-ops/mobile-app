package io.music_assistant.client.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.media.MediaSessionManager
import coil3.BitmapImage
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ARTWORK_SIZE_PX = 512
private const val JPEG_QUALITY = 88
private const val MAX_PENDING_WRITERS = 4
private const val MAX_SOURCE_URL_LENGTH = 4_096
private const val MAX_ENCODED_TOKEN_LENGTH = 8_192
private const val AES_KEY_BYTES = 32
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val TOKEN_PREFERENCES = "android_auto_artwork"
private const val TOKEN_KEY = "token_key"
private const val ARTWORK_PATH = "art"
private const val UNKNOWN_CALLER_PID = -1

/**
 * Maps artwork URLs to self-contained authenticated encrypted tokens.
 *
 * The source URL is never exposed in plaintext. Because the installation key is persisted, a
 * media host can reuse a cached content URI after the Music Assistant process is recreated.
 */
internal class AutoArtworkTokenCodec(keyBytes: ByteArray) {
    private val key = SecretKeySpec(
        keyBytes.copyOf().also {
            require(it.size == AES_KEY_BYTES) { "Artwork token key must be 256 bits" }
        },
        "AES",
    )

    fun uriFor(authority: String, sourceUrl: String): Uri? {
        if (!isSupportedSource(sourceUrl)) return null
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(sourceUrl.toByteArray(Charsets.UTF_8))
        }
        val token = Base64.encodeToString(
            iv + encrypted,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return Uri.Builder()
            .scheme("content")
            .authority(authority)
            .appendPath(ARTWORK_PATH)
            .appendPath(token)
            .build()
    }

    fun sourceFor(authority: String, uri: Uri): String? {
        if (uri.scheme != "content" || uri.authority != authority) return null
        if (uri.pathSegments.size != 2 || uri.pathSegments.first() != ARTWORK_PATH) return null
        val token = uri.pathSegments[1]
        if (token.length > MAX_ENCODED_TOKEN_LENGTH) return null
        val payload = runCatching {
            Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        if (payload.size <= GCM_IV_BYTES) return null
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val sourceUrl = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                doFinal(encrypted).toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null
        return sourceUrl.takeIf(::isSupportedSource)
    }

    private fun isSupportedSource(sourceUrl: String): Boolean {
        if (sourceUrl.isBlank() || sourceUrl.length > MAX_SOURCE_URL_LENGTH) return false
        val uri = Uri.parse(sourceUrl)
        if (uri.userInfo != null) return false
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> !uri.host.isNullOrBlank()
            "mawebrtc" -> !uri.authority.isNullOrBlank() || uri.pathSegments.isNotEmpty()
            else -> false
        }
    }
}

/** Artwork bridge shared by media-description creation and the provider. */
internal object AndroidAutoArtwork {
    @Volatile
    private var configuredAuthority: String? = null

    @Volatile
    private var codec: AutoArtworkTokenCodec? = null

    val authority: String
        get() = requireNotNull(configuredAuthority) { "Artwork bridge is not initialized" }

    val rootUri: Uri
        get() = Uri.parse("content://$authority/$ARTWORK_PATH")

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        val applicationAuthority = "${applicationContext.packageName}.autoartwork"
        if (codec != null && configuredAuthority == applicationAuthority) return
        synchronized(this) {
            if (codec != null && configuredAuthority == applicationAuthority) return
            check(configuredAuthority == null || configuredAuthority == applicationAuthority) {
                "Artwork bridge cannot change authority within one process"
            }
            configuredAuthority = applicationAuthority
            val preferences = applicationContext.getSharedPreferences(
                TOKEN_PREFERENCES,
                Context.MODE_PRIVATE,
            )
            val storedKey = preferences.getString(TOKEN_KEY, null)
            val keyBytes = storedKey?.let {
                runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
            }?.takeIf { it.size == AES_KEY_BYTES }
                ?: ByteArray(AES_KEY_BYTES).also { generated ->
                    SecureRandom().nextBytes(generated)
                    val persisted = preferences.edit()
                        .putString(TOKEN_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
                        .commit()
                    if (!persisted) {
                        androidAutoLog.e { "Unable to persist Android Auto artwork token key" }
                        return@synchronized
                    }
                }
            codec = AutoArtworkTokenCodec(keyBytes)
        }
    }

    fun uriFor(sourceUrl: String): Uri? = codec?.uriFor(authority, sourceUrl)
    fun sourceFor(uri: Uri): String? = codec?.sourceFor(authority, uri)

    @Suppress("DEPRECATION")
    fun grantReadAccess(
        context: Context,
        packageName: String,
        uid: Int,
        isTrusted: ((packageName: String, uid: Int) -> Boolean)? = null,
    ): Boolean {
        val packagesForUid = context.packageManager.getPackagesForUid(uid).orEmpty()
        if (packageName !in packagesForUid) return false
        val trusted = if (uid == context.applicationInfo.uid) {
            true
        } else {
            isTrusted?.invoke(packageName, uid) ?: run {
                val remoteUser = MediaSessionManager.RemoteUserInfo(
                    packageName,
                    UNKNOWN_CALLER_PID,
                    uid,
                )
                MediaSessionManager.getSessionManager(context).isTrustedForMediaControl(remoteUser)
            }
        }
        if (!trusted) return false
        context.grantUriPermission(
            packageName,
            rootUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        return true
    }

    fun revokeReadAccess(context: Context, packageName: String) {
        context.revokeUriPermission(
            packageName,
            rootUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

/**
 * Read-only, grant-scoped artwork provider for Android Auto and other MediaBrowser hosts.
 *
 * The media host receives only a local content URI. This provider resolves the authenticated
 * source token, fetches under the Music Assistant UID (and therefore its VPN route), downsamples
 * it, and streams a bounded JPEG. No bitmap is placed in a MediaBrowser Binder transaction.
 */
class AndroidAutoArtworkProvider : ContentProvider() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerSlots = Semaphore(MAX_PENDING_WRITERS)
    private val logger = androidAutoLog.withTag("ArtworkProvider")

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        AndroidAutoArtwork.initialize(providerContext)
        return true
    }

    override fun getType(uri: Uri): String? =
        AndroidAutoArtwork.sourceFor(uri)?.let { "image/jpeg" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Android Auto artwork is read-only")
        val sourceUrl = AndroidAutoArtwork.sourceFor(uri)
            ?: throw FileNotFoundException("Unknown Android Auto artwork")
        val providerContext = context ?: throw FileNotFoundException("Provider unavailable")
        if (!writerSlots.tryAcquire()) {
            throw FileNotFoundException("Android Auto artwork provider is busy")
        }
        val pipe = runCatching { ParcelFileDescriptor.createReliablePipe() }
            .getOrElse { error ->
                writerSlots.release()
                throw FileNotFoundException("Unable to create artwork pipe").apply {
                    initCause(error)
                }
            }
        val (readSide, writeSide) = pipe

        scope.launch {
            try {
                val jpeg = loadArtwork(providerContext, sourceUrl)
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                    output.write(jpeg)
                }
            } catch (error: Throwable) {
                runCatching { writeSide.closeWithError("Artwork unavailable") }
                logger.w { "Unable to serve Android Auto artwork (${error::class.simpleName})" }
            } finally {
                writerSlots.release()
            }
        }
        return readSide
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (AndroidAutoArtwork.sourceFor(uri) == null) return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val tokenPrefix = uri.lastPathSegment.orEmpty().take(16)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> "$tokenPrefix.jpg"
                        OpenableColumns.SIZE -> null
                        else -> null
                    }
                },
            )
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private suspend fun loadArtwork(context: Context, sourceUrl: String): ByteArray {
        val result = SingletonImageLoader.get(context).execute(
            ImageRequest.Builder(context)
                .data(sourceUrl)
                .size(ARTWORK_SIZE_PX)
                .allowHardware(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build(),
        ) as? SuccessResult
        val image = result?.image ?: throw FileNotFoundException("Artwork fetch failed")
        val bitmap = image.toArtworkBitmap()
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Artwork encode failed"
            }
            output.toByteArray()
        }
    }

    private fun Image.toArtworkBitmap(): Bitmap {
        if (this is BitmapImage) return bitmap
        val outputWidth = width.takeIf { it > 0 }?.coerceAtMost(ARTWORK_SIZE_PX) ?: ARTWORK_SIZE_PX
        val outputHeight = height.takeIf { it > 0 }?.coerceAtMost(ARTWORK_SIZE_PX) ?: ARTWORK_SIZE_PX
        return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).run {
                drawColor(Color.BLACK)
                this@toArtworkBitmap.draw(this)
            }
        }
    }
}
