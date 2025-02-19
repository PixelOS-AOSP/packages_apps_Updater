/*
 * Copyright (C) 2017-2022 The LineageOS Project
 * Copyright (C) 2020-2022 SHIFT GmbH
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
package net.pixelos.ota

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.RecoverySystem
import android.util.Log
import net.pixelos.ota.controller.UpdaterController
import net.pixelos.ota.misc.StringGenerator
import net.pixelos.ota.misc.Utils
import net.pixelos.ota.model.Update
import net.pixelos.ota.model.UpdateStatus
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.util.zip.ZipFile

class UpdateImporter(
    private val activity: Activity,
    private val callbacks: Callbacks,
) {
    private var workingThread: Thread? = null

    fun stopImport() {
        if (workingThread != null && workingThread!!.isAlive) {
            workingThread!!.interrupt()
            workingThread = null
        }
    }

    fun openImportPicker() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME_ZIP)
        activity.startActivityForResult(intent, REQUEST_PICK)
    }

    fun onResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent,
    ): Boolean {
        if (resultCode != Activity.RESULT_OK || requestCode != REQUEST_PICK) {
            return false
        }

        return onPicked(data.data!!)
    }

    private fun onPicked(uri: Uri): Boolean {
        callbacks.onImportStarted()

        workingThread =
            Thread {
                var importedFile: File? = null
                try {
                    importedFile = importFile(uri)
                    verifyPackage(importedFile)

                    val update = buildLocalUpdate(importedFile)
                    addUpdate(update)
                    activity.runOnUiThread { callbacks.onImportCompleted(update) }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import update package", e)
                    // Do not store invalid update
                    importedFile?.delete()

                    activity.runOnUiThread { callbacks.onImportCompleted(null) }
                }
            }
        workingThread!!.start()
        return true
    }

    @SuppressLint("SetWorldReadable")
    @Throws(IOException::class)
    private fun importFile(uri: Uri): File {
        val parcelDescriptor =
            activity.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Failed to obtain fileDescriptor")

        val iStream = FileInputStream(parcelDescriptor.fileDescriptor)
        val downloadDir = Utils.getDownloadPath(activity)
        val outFile = File(downloadDir, FILE_NAME)
        if (outFile.exists()) {
            outFile.delete()
        }
        val oStream = FileOutputStream(outFile)

        var read: Int
        val buffer = ByteArray(4096)
        while ((iStream.read(buffer).also { read = it }) > 0) {
            oStream.write(buffer, 0, read)
        }
        oStream.flush()
        oStream.close()
        iStream.close()
        parcelDescriptor.close()

        outFile.setReadable(true, false)

        return outFile
    }

    private fun buildLocalUpdate(file: File): Update {
        val timeStamp = getTimeStamp(file)
        val buildDate = StringGenerator.getDateLocalizedUTC(activity, DateFormat.MEDIUM, timeStamp)
        val name = activity.getString(R.string.local_update_import)
        val update = Update()
        update.availableOnline = false
        update.name = name
        update.file = file
        update.fileSize = file.length()
        update.downloadId = Update.LOCAL_ID
        update.timestamp = timeStamp
        update.status = UpdateStatus.VERIFIED
        update.persistentStatus = UpdateStatus.Persistent.VERIFIED
        update.version = String.format("%s (%s)", name, buildDate)
        return update
    }

    @Throws(Exception::class)
    private fun verifyPackage(file: File) {
        try {
            RecoverySystem.verifyPackage(file, null, null)
        } catch (e: Exception) {
            if (file.exists()) {
                file.delete()
                throw Exception("Verification failed, file has been deleted")
            } else {
                throw e
            }
        }
    }

    private fun addUpdate(update: Update) {
        val controller = UpdaterController.getInstance(activity)
        controller.addUpdate(update, false)
    }

    private fun getTimeStamp(file: File): Long {
        try {
            val metadataContent = readZippedFile(file)
            val lines =
                metadataContent.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (line in lines) {
                if (!line.startsWith(METADATA_TIMESTAMP_KEY)) {
                    continue
                }

                val timeStampStr = line.replace(METADATA_TIMESTAMP_KEY, "")
                return timeStampStr.toLong()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read date from local update zip package", e)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Failed to parse timestamp number from zip metadata file", e)
        }

        Log.e(TAG, "Couldn't find timestamp in zip file, falling back to \$now")
        return System.currentTimeMillis()
    }

    @Throws(IOException::class)
    private fun readZippedFile(file: File): String {
        val sb = StringBuilder()
        var iStream: InputStream? = null

        try {
            ZipFile(file).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    val entry = iterator.nextElement()
                    if (METADATA_PATH != entry.name) {
                        continue
                    }

                    iStream = zip.getInputStream(entry)
                    break
                }

                if (iStream == null) {
                    throw FileNotFoundException("Couldn't find " + METADATA_PATH + " in " + file.name)
                }

                val buffer = ByteArray(1024)
                var read: Int
                while ((iStream!!.read(buffer).also { read = it }) > 0) {
                    sb.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read file from zip package", e)
            throw e
        } finally {
            if (iStream != null) {
                iStream!!.close()
            }
        }

        return sb.toString()
    }

    interface Callbacks {
        fun onImportStarted()

        fun onImportCompleted(update: Update?)
    }

    companion object {
        private const val REQUEST_PICK = 9061
        private const val TAG = "UpdateImporter"
        private const val MIME_ZIP = "application/zip"
        private const val FILE_NAME = "localUpdate.zip"
        private const val METADATA_PATH = "META-INF/com/android/metadata"
        private const val METADATA_TIMESTAMP_KEY = "post-timestamp="
    }
}
