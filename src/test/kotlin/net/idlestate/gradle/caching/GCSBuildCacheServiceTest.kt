/**
 * Copyright 2019 Thorsten Ehlers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.idlestate.gradle.caching

import com.google.cloud.ReadChannel
import com.google.cloud.storage.Blob
import com.google.cloud.storage.Bucket
import com.google.cloud.storage.CopyWriter
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import com.google.common.io.FileBackedOutputStream
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheException
import org.gradle.caching.BuildCacheKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime

class GCSBuildCacheServiceTest {
    private val bucket = Mockito.mock(Bucket::class.java)
    private val storage = Mockito.mock(Storage::class.java)
    private val blob = Mockito.mock(Blob::class.java)
    private val channel = Mockito.mock(ReadChannel::class.java)
    private val key = Mockito.mock(BuildCacheKey::class.java).also {
        Mockito.`when`(it.hashCode).thenReturn("key")
    }

    init {
        Mockito.`when`(storage.get("bucket")).thenReturn(bucket)
    }

    @After
    fun clearCallback() {
        GCSBuildCacheService.storageExceptionCallback = null
    }

    @Test
    fun oldHitRestoresOnceAndAwaitsServerSideRefresh() {
        Mockito.`when`(bucket.get("prefix/key")).thenReturn(blob)
        Mockito.`when`(blob.createTimeOffsetDateTime).thenReturn(OffsetDateTime.now().minusDays(2))
        val copy = Mockito.mock(CopyWriter::class.java)
        Mockito.`when`(blob.copyTo("bucket", "prefix/key")).thenReturn(copy)
        Mockito.`when`(copy.result).thenReturn(blob)
        val payload = "cached result".toByteArray()
        var sent = false
        Mockito.`when`(channel.read(any(ByteBuffer::class.java))).thenAnswer { invocation ->
            if (sent) {
                -1
            } else {
                invocation.getArgument<ByteBuffer>(0).put(payload)
                sent = true
                payload.size
            }
        }
        Mockito.`when`(blob.reader()).thenReturn(channel)
        var restored = byteArrayOf()

        assertTrue(service().load(key, BuildCacheEntryReader { restored = it.readBytes() }))

        assertArrayEquals(payload, restored)
        verify(blob, never()).getContent()
        verify(copy).result
        verify(channel, atLeastOnce()).close()
        verify(bucket, never()).create(Mockito.anyString(), any(ByteArray::class.java))
    }

    @Test
    fun freshHitDoesNotRefresh() {
        Mockito.`when`(bucket.get("prefix/key")).thenReturn(blob)
        Mockito.`when`(blob.reader()).thenReturn(channel)
        Mockito.`when`(blob.createTimeOffsetDateTime).thenReturn(OffsetDateTime.now())

        assertTrue(service().load(key, BuildCacheEntryReader { }))

        verify(blob, never()).copyTo("bucket", "prefix/key")
        verify(blob, never()).getContent()
        verify(channel, atLeastOnce()).close()
    }

    @Test
    fun disabledRefreshDoesNotCopyOldHit() {
        Mockito.`when`(bucket.get("prefix/key")).thenReturn(blob)
        Mockito.`when`(blob.reader()).thenReturn(channel)

        assertTrue(service(0).load(key, BuildCacheEntryReader { }))

        verify(blob, never()).copyTo("bucket", "prefix/key")
        verify(blob, never()).getContent()
    }

    @Test
    fun missingEntryDoesNotInvokeReader() {
        val reader = Mockito.mock(BuildCacheEntryReader::class.java)

        assertFalse(service().load(key, reader))

        Mockito.verifyNoInteractions(reader)
    }

    @Test
    fun status404IsAMissWithout404InMessage() {
        Mockito.`when`(bucket.get("prefix/key")).thenThrow(StorageException(404, "not found"))
        assertFalse(service().load(key, BuildCacheEntryReader { }))
    }

    @Test
    fun otherStatusesAreNotMistakenFor404Messages() {
        val failure = StorageException(500, "request 404 failed")
        Mockito.`when`(bucket.get("prefix/key")).thenThrow(failure)

        val error = assertThrows(BuildCacheException::class.java) {
            service().load(key, BuildCacheEntryReader { })
        }

        assertSame(failure, error.cause)
    }

    @Test
    fun readerFailureClosesTheChannelWithoutRefreshing() {
        Mockito.`when`(bucket.get("prefix/key")).thenReturn(blob)
        Mockito.`when`(blob.reader()).thenReturn(channel)
        val failure = IOException("reader failed")

        val error = assertThrows(IOException::class.java) {
            service().load(key, BuildCacheEntryReader { throw failure })
        }

        assertSame(failure, error)
        verify(channel, atLeastOnce()).close()
        verify(blob, never()).copyTo("bucket", "prefix/key")
    }

    @Test
    fun writerFailureClosesTheWriteBuffer() {
        val service = service()
        val failure = IOException("writer failed")
        val writer = Mockito.mock(BuildCacheEntryWriter::class.java)
        Mockito.doThrow(failure).`when`(writer).writeTo(any(java.io.OutputStream::class.java))
        Mockito.mockConstruction(FileBackedOutputStream::class.java).use { buffers ->
            val error = assertThrows(IOException::class.java) {
                service.store(key, writer)
            }
            assertSame(failure, error)
            verify(buffers.constructed().single()).close()
        }
    }

    @Test
    fun initializationFailureRetainsDiscordAuthCallback() {
        val failure = StorageException(401, "unauthorized")
        Mockito.`when`(storage.get("bucket")).thenThrow(failure)
        var observed: StorageException? = null
        GCSBuildCacheService.storageExceptionCallback = { observed = it }

        val error = assertThrows(BuildCacheException::class.java) { service() }

        assertSame(failure, observed)
        assertSame(failure, error.cause)
        assertTrue(error.message!!.contains("status 401"))
    }

    private fun service(refreshAfterSeconds: Long = 86400): GCSBuildCacheService {
        val options = Mockito.mock(StorageOptions::class.java)
        val builder = Mockito.mock(StorageOptions.Builder::class.java)
        Mockito.`when`(builder.build()).thenReturn(options)
        Mockito.`when`(options.service).thenReturn(storage)
        Mockito.mockStatic(StorageOptions::class.java).use { factory ->
            factory.`when`<StorageOptions.Builder> { StorageOptions.newBuilder() }.thenReturn(builder)
            return GCSBuildCacheService("bucket", "prefix", refreshAfterSeconds, 8388608)
        }
    }
}
