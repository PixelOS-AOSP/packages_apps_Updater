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
package net.pixelos.ota.misc

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

object FileUtils {
    private const val TAG = "FileUtils"

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(
        sourceFile: File,
        destFile: File,
        progressCallBack: ProgressCallBack?,
    ) {
        try {
            FileInputStream(sourceFile).channel.use { sourceChannel ->
                FileOutputStream(destFile).channel.use { destChannel ->
                    if (progressCallBack != null) {
                        val readableByteChannel: ReadableByteChannel =
                            CallbackByteChannel(
                                sourceChannel,
                                sourceFile.length(),
                                progressCallBack,
                            )
                        destChannel.transferFrom(readableByteChannel, 0, sourceChannel.size())
                    } else {
                        destChannel.transferFrom(sourceChannel, 0, sourceChannel.size())
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not copy file", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            throw e
        }
    }

    interface ProgressCallBack {
        fun update(progress: Int)
    }

    private class CallbackByteChannel(
        private val mReadableByteChannel: ReadableByteChannel,
        private val mSize: Long,
        private val mCallback: ProgressCallBack,
    ) : ReadableByteChannel {
        private var mSizeRead: Long = 0
        private var mProgress = 0

        @Throws(IOException::class)
        override fun close() {
            mReadableByteChannel.close()
        }

        override fun isOpen(): Boolean = mReadableByteChannel.isOpen

        @Throws(IOException::class)
        override fun read(bb: ByteBuffer): Int {
            var read: Int
            if ((mReadableByteChannel.read(bb).also { read = it }) > 0) {
                mSizeRead += read.toLong()
                val progress = if (mSize > 0) Math.round(mSizeRead * 100f / mSize) else -1
                if (mProgress != progress) {
                    mCallback.update(progress)
                    mProgress = progress
                }
            }
            return read
        }
    }
}
