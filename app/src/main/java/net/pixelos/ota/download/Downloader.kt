/*
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

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class Downloader {
    companion object {
        private const val TIMEOUT_CONNECT = 15000 // 15 seconds
        private const val TIMEOUT_READ = 30000 // 30 seconds
        private const val BUFFER_SIZE = 262144 // 256 KB
        private const val TAG = "Downloader"

        @JvmStatic
        suspend fun asString(mUrl: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    val url = URL(mUrl)
                    val urlConn = url.openConnection() as HttpURLConnection
                    urlConn.apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_CONNECT
                        readTimeout = TIMEOUT_READ
                        doInput = true
                    }
                    urlConn.connect()

                    BufferedReader(InputStreamReader(urlConn.inputStream)).use { it.readText() }
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Request timed out", e)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching data", e)
                    null
                }
            }

        @JvmStatic
        suspend fun downloadApk(
            path: String?,
            mUrl: String?,
        ): Boolean {
            if (path.isNullOrEmpty() || mUrl.isNullOrEmpty()) {
                return false
            }

            return withContext(Dispatchers.IO) {
                try {
                    val file = File(path).apply { if (exists()) delete() }
                    val url = URL(mUrl)
                    val urlConn = url.openConnection() as HttpURLConnection
                    urlConn.apply {
                        connectTimeout = TIMEOUT_CONNECT
                        readTimeout = TIMEOUT_READ
                        requestMethod = "GET"
                        doInput = true
                    }
                    urlConn.connect()

                    urlConn.inputStream.use { inputStream ->
                        FileOutputStream(file).use { os ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                os.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    Log.d(TAG, "Download successful: $path")
                    true
                } catch (e: SocketTimeoutException) {
                    Log.e(TAG, "Download timed out", e)
                    File(path).delete()
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed", e)
                    File(path).delete()
                    false
                }
            }
        }
    }
}
