package io.music_assistant.client.auto

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.music_assistant.client.data.model.client.ImageInfo
import io.music_assistant.client.data.model.client.ImageType
import io.music_assistant.client.data.model.client.items.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutoArtworkUriTest {
    @Before
    fun initializeArtworkBridge() {
        AndroidAutoArtwork.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `media description routes remote artwork through local resolver`() {
        val remoteUrl = "https://tailnet.invalid/imageproxy/opaque-id?size=512"
        val localUri = Uri.parse("content://io.music_assistant.client.autoartwork/art/local-id")
        val track = trackWithArtwork(remoteUrl)

        val description = track.toMediaDescription(
            defaultIconUri = DEFAULT_ICON,
            artworkUri = { source ->
                assertEquals(remoteUrl, source)
                localUri
            },
        )

        assertEquals(localUri, description.iconUri)
        assertFalse(description.iconUri.toString().contains(remoteUrl))
    }

    @Test
    fun `default media description contains only a local uri and no bitmap`() {
        val remoteUrl = "https://tailnet.invalid/imageproxy/default-path"
        val description = trackWithArtwork(remoteUrl).toMediaDescription(DEFAULT_ICON)
        val iconUri = requireNotNull(description.iconUri)

        assertEquals("content", iconUri.scheme)
        assertEquals(
            "${ApplicationProvider.getApplicationContext<Context>().packageName}.autoartwork",
            iconUri.authority,
        )
        assertEquals(AndroidAutoArtwork.authority, iconUri.authority)
        assertEquals(remoteUrl, AndroidAutoArtwork.sourceFor(iconUri))
        assertFalse(iconUri.toString().contains("tailnet.invalid"))
        assertNull(description.iconBitmap)
    }

    @Test
    fun `media description keeps default icon when artwork is absent`() {
        val description = trackWithArtwork(null).toMediaDescription(
            defaultIconUri = DEFAULT_ICON,
            artworkUri = { error("resolver must not run without artwork") },
        )

        assertEquals(DEFAULT_ICON, description.iconUri)
    }

    @Test
    fun `codec emits opaque authenticated content uris that survive process recreation`() {
        val key = ByteArray(32) { it.toByte() }
        val codec = AutoArtworkTokenCodec(key)
        val restoredCodec = AutoArtworkTokenCodec(key)
        val authority = "io.music_assistant.client.autoartwork"
        val source = "https://tailnet.invalid/imageproxy/first?token=secret"
        val uri = requireNotNull(codec.uriFor(authority, source))

        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertFalse(uri.toString().contains("tailnet.invalid"))
        assertFalse(uri.toString().contains("secret"))
        assertEquals(source, codec.sourceFor(authority, uri))
        assertEquals(source, restoredCodec.sourceFor(authority, uri))

        val token = requireNotNull(uri.lastPathSegment)
        val changedIndex = token.length / 2
        val replacement = if (token[changedIndex] == 'A') 'B' else 'A'
        val tamperedToken = token.replaceRange(changedIndex, changedIndex + 1, replacement.toString())
        val tamperedUri = uri.buildUpon().path("/art/$tamperedToken").build()
        assertNull(codec.sourceFor(authority, tamperedUri))
    }

    @Test
    fun `codec rejects local credential-bearing and oversized sources`() {
        val codec = AutoArtworkTokenCodec(ByteArray(32))
        val authority = "io.music_assistant.client.autoartwork"

        assertNull(codec.uriFor(authority, "file:///data/user/0/private.jpg"))
        assertNull(codec.uriFor(authority, "content://private.provider/image"))
        assertNull(codec.uriFor(authority, "https://user:password@example.com/image.jpg"))
        assertNull(codec.uriFor(authority, "https://example.com/${"x".repeat(4_097)}"))
        assertNotNull(codec.uriFor(authority, "http://192.168.1.2:8095/imageproxy/id"))
        assertNotNull(codec.uriFor(authority, "mawebrtc://image/id"))
    }

    @Test
    fun `manifest keeps artwork provider private and grantable`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        @Suppress("DEPRECATION")
        val provider = context.packageManager.resolveContentProvider(
            AndroidAutoArtwork.authority,
            PackageManager.GET_META_DATA,
        )

        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun `provider exposes only opaque read metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = "https://tailnet.invalid/imageproxy/private-source"
        val uri = requireNotNull(AndroidAutoArtwork.uriFor(source))

        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            val name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            assertTrue(name.endsWith(".jpg"))
            assertFalse(name.contains("tailnet.invalid"))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    @Test
    fun `artwork grant accepts the trusted app uid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(
            AndroidAutoArtwork.grantReadAccess(
                context,
                context.packageName,
                Process.myUid(),
            ),
        )
        AndroidAutoArtwork.revokeReadAccess(context, context.packageName)
    }

    @Test
    fun `artwork grant rejects a package that does not own the caller uid`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(
            AndroidAutoArtwork.grantReadAccess(
                context,
                context.packageName,
                Process.myUid() + 1,
            ),
        )
    }

    private fun trackWithArtwork(url: String?): Track = Track(
        itemId = "track-1",
        provider = "library",
        name = "Track",
        providerMappings = null,
        metadata = null,
        favorite = false,
        uri = "library://track/1",
        images = url?.let {
            mapOf(
                ImageType.THUMB to ImageInfo(
                    type = ImageType.THUMB,
                    path = "cover.jpg",
                    isRemotelyAccessible = false,
                    provider = "library",
                    url = it,
                ),
            )
        }.orEmpty(),
        duration = 180.0,
        isPlayable = true,
        artists = emptyList(),
        album = null,
        discNumber = 1,
        trackNumber = 1,
        position = null,
        version = null,
    )

    private companion object {
        val DEFAULT_ICON: Uri = Uri.parse("android.resource://io.music_assistant.client/drawable/default")
    }
}
