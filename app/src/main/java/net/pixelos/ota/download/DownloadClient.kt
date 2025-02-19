/*
 * Copyright (C) 2017-2022 The LineageOS Project
 * Copyright (C) 2025 PixelOS
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

import java.io.File
import java.io.IOException

interface DownloadClient {
    /** Start the download. This method has no effect if the download already started. */
    fun start()

    /**
     * Resume the download. The download will fail if the server can't fulfill the partial content
     * request and [DownloadCallback.onFailure] will be called. This method has no effect if the
     * download already started or the destination file doesn't exist.
     */
    fun resume()

    /** Cancel the download. This method has no effect if the download isn't ongoing. */
    fun cancel()

    interface DownloadCallback {
        fun onResponse(headers: Headers)

        fun onSuccess()

        fun onFailure(cancelled: Boolean)
    }

    interface ProgressListener {
        fun update(
            bytesRead: Long,
            contentLength: Long,
            speed: Long,
            eta: Long,
        )
    }

    interface Headers {
        fun get(name: String): String?
    }

    class Builder {
        private var url: String? = null
        private var destination: File? = null
        private var callback: DownloadCallback? = null
        private var progressListener: ProgressListener? = null
        private var useDuplicateLinks: Boolean = false

        @Throws(IOException::class)
        fun build(): DownloadClient {
            val url = this.url ?: throw IllegalStateException("No download URL defined")
            val destination =
                this.destination ?: throw IllegalStateException("No download destination defined")
            val callback =
                this.callback ?: throw IllegalStateException("No download callback defined")

            return HttpURLConnectionClient(
                url,
                destination,
                progressListener,
                callback,
                useDuplicateLinks,
            )
        }

        fun setUrl(url: String) = apply { this.url = url }

        fun setDestination(destination: File) = apply { this.destination = destination }

        fun setDownloadCallback(callback: DownloadCallback) = apply { this.callback = callback }

        fun setProgressListener(progressListener: ProgressListener?) =
            apply {
                this.progressListener = progressListener
            }

        fun setUseDuplicateLinks(useDuplicateLinks: Boolean) =
            apply {
                this.useDuplicateLinks = useDuplicateLinks
            }
    }
}
