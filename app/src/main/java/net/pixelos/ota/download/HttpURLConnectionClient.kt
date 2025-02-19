/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota.download

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.PriorityQueue
import java.util.regex.Pattern

class HttpURLConnectionClient(
    url: String?,
    private val destination: File,
    private val progressListener: DownloadClient.ProgressListener?,
    private val callback: DownloadClient.DownloadCallback,
    private val useDuplicateLinks: Boolean,
) : DownloadClient {
    private var client: HttpURLConnection = URL(url).openConnection() as HttpURLConnection
    private var downloadThread: DownloadThread? = null

    override fun start() {
        if (downloadThread != null) {
            Log.e(TAG, "Already downloading")
            return
        }
        downloadFileInternalCommon(false)
    }

    override fun resume() {
        if (downloadThread != null) {
            Log.e(TAG, "Already downloading")
            return
        }
        downloadFileResumeInternal()
    }

    override fun cancel() {
        downloadThread?.interrupt()
        downloadThread = null
    }

    private fun downloadFileResumeInternal() {
        if (!destination.exists()) {
            callback.onFailure(false)
            return
        }
        val offset = destination.length()
        client.setRequestProperty("Range", "bytes=$offset-")
        downloadFileInternalCommon(true)
    }

    private fun downloadFileInternalCommon(resume: Boolean) {
        if (downloadThread != null) {
            Log.wtf(TAG, "Already downloading")
            return
        }

        downloadThread = DownloadThread(resume).also { it.start() }
    }

    private inner class Headers : DownloadClient.Headers {
        override fun get(name: String): String? = client.getHeaderField(name)
    }

    private inner class DownloadThread(
        private val resume: Boolean,
    ) : Thread() {
        private var totalBytes: Long = 0
        private var totalBytesRead: Long = 0
        private var curSampleBytes: Long = 0
        private var lastMillis: Long = 0
        private var speed: Long = -1
        private var eta: Long = -1

        private fun calculateSpeed(justResumed: Boolean) {
            val millis = SystemClock.elapsedRealtime()
            if (justResumed) {
                lastMillis = millis
                speed = -1
                curSampleBytes = totalBytesRead
                return
            }
            val delta = millis - lastMillis
            if (delta > 500) {
                val curSpeed = ((totalBytesRead - curSampleBytes) * 1000) / delta
                speed = if (speed == -1L) curSpeed else ((speed * 3) + curSpeed) / 4

                lastMillis = millis
                curSampleBytes = totalBytesRead
            }
        }

        private fun calculateEta() {
            if (speed > 0) {
                eta = (totalBytes - totalBytesRead) / speed
            }
        }

        @Throws(IOException::class)
        private fun changeClientUrl(newUrl: URL) {
            val range = client.getRequestProperty("Range")
            client.disconnect()
            client = newUrl.openConnection() as HttpURLConnection
            range?.let { client.setRequestProperty("Range", it) }
        }

        @Throws(IOException::class)
        private fun handleDuplicateLinks() {
            val protocol = client.url.protocol
            val duplicates = PriorityQueue<DuplicateLink>(Comparator.comparingInt { it.priority })

            client.headerFields["Link"]?.forEach { field ->
                // https://tools.ietf.org/html/rfc6249
                // https://tools.ietf.org/html/rfc5988#section-5
                val matcher =
                    Pattern
                        .compile("(?i)<(.+)>\\s*;\\s*rel=duplicate(?:.*pri=([0-9]+).*|.*)?")
                        .matcher(field)
                if (matcher.matches()) {
                    val url = matcher.group(1)
                    val priority = matcher.group(2)?.toInt() ?: 999999
                    duplicates.add(DuplicateLink(url, priority))
                    Log.d(TAG, "Adding duplicate link $url")
                } else {
                    Log.d(TAG, "Ignoring link $field")
                }
            }

            var newUrl = client.getHeaderField("Location")
            while (true) {
                try {
                    val url = URL(newUrl)
                    // If we hadn't handled duplicate links, we wouldn't have
                    // used this url.
                    if (url.protocol != protocol) throw IOException("Protocol changes are not allowed")
                    Log.d(TAG, "Downloading from $newUrl")
                    changeClientUrl(url)
                    client.connectTimeout = 5000
                    client.connect()
                    if (!client.responseCode.isSuccessCode()) {
                        throw IOException("Server replied with ${client.responseCode}")
                    }
                    return
                } catch (e: IOException) {
                    duplicates.poll()?.let {
                        newUrl = it.url
                        Log.e(TAG, "Using duplicate link ${it.url}", e)
                    } ?: throw e
                }
            }
        }

        override fun run() {
            var justResumed = false
            try {
                client.instanceFollowRedirects = !useDuplicateLinks
                client.connect()
                var responseCode = client.responseCode

                if (useDuplicateLinks && responseCode.isRedirectCode()) {
                    handleDuplicateLinks()
                    responseCode = client.responseCode
                }

                callback.onResponse(Headers())

                if (resume && responseCode.isPartialContentCode()) {
                    justResumed = true
                    totalBytesRead = destination.length()
                    Log.d(TAG, "The server fulfilled the partial content request")
                } else if (resume || !responseCode.isSuccessCode()) {
                    Log.e(TAG, "The server replied with code $responseCode")
                    callback.onFailure(isInterrupted)
                    return
                }

                client.inputStream.use { inputStream ->
                    FileOutputStream(destination, resume).use { outputStream ->
                        totalBytes = client.contentLengthLong + totalBytesRead
                        val buffer = ByteArray(8192)
                        var count = 0

                        while (!isInterrupted && inputStream.read(buffer).also { count = it } > 0) {
                            outputStream.write(buffer, 0, count)
                            totalBytesRead += count.toLong()
                            calculateSpeed(justResumed)
                            calculateEta()
                            justResumed = false
                            progressListener?.update(totalBytesRead, totalBytes, speed, eta)
                        }

                        progressListener?.update(totalBytesRead, totalBytes, speed, eta)
                        outputStream.flush()
                        if (isInterrupted) callback.onFailure(true) else callback.onSuccess()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error downloading file", e)
                callback.onFailure(isInterrupted)
            } finally {
                client.disconnect()
            }
        }
    }

    private data class DuplicateLink(
        val url: String?,
        val priority: Int,
    )

    companion object {
        private const val TAG = "HttpURLConnectionClient"

        private fun Int.isSuccessCode() = this / 100 == 2

        private fun Int.isRedirectCode() = this / 100 == 3

        private fun Int.isPartialContentCode() = this == 206
    }
}
