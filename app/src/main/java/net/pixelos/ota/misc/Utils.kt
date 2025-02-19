/*
 * Copyright (C) 2017-2023 The LineageOS Project
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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.text.format.DateFormat
import android.util.Log
import androidx.preference.PreferenceManager
import net.pixelos.ota.R
import net.pixelos.ota.UpdatesDbHelper
import net.pixelos.ota.controller.UpdaterService
import net.pixelos.ota.model.Update
import net.pixelos.ota.model.UpdateBaseInfo
import net.pixelos.ota.model.UpdateInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Enumeration
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object Utils {
    private const val TAG: String = "Utils"

    @JvmStatic
    fun getDownloadPath(context: Context): File = File(context.getString(R.string.download_path))

    @JvmStatic
    fun getCachedUpdateList(context: Context): File = File(context.cacheDir, "updates.json")

    // This should really return an UpdateBaseInfo object, but currently this only
    // used to initialize UpdateInfo objects
    @Throws(JSONException::class)
    private fun parseJsonUpdate(obj: JSONObject): UpdateInfo {
        val update = Update()
        update.timestamp = obj.getLong("datetime")
        update.name = obj.getString("filename")
        update.downloadId = obj.getString("id")
        update.fileSize = obj.getLong("size")
        update.downloadUrl = obj.getString("url")
        update.version = obj.getString("version")
        return update
    }

    private fun isCompatible(update: UpdateBaseInfo): Boolean {
        if (SystemProperties.get(Build.VERSION.RELEASE) > update.version) {
            Log.d(
                TAG,
                (
                    update.name +
                        " with version " +
                        update.version +
                        " is older than current Android version " +
                        SystemProperties.get(Constants.PROP_BUILD_VERSION)
                ),
            )
            return false
        }
        if (update.timestamp <= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) {
            Log.d(TAG, update.name + " is older than/equal to the current build")
            return false
        }

        return true
    }

    @JvmStatic
    fun canInstall(update: UpdateBaseInfo): Boolean = update.timestamp > SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)

    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun parseJson(
        file: File?,
        compatibleOnly: Boolean,
    ): List<UpdateInfo> {
        val updates: MutableList<UpdateInfo> = ArrayList()
        val json: StringBuilder = StringBuilder()
        BufferedReader(FileReader(file)).use { br ->
            var line: String?
            while ((br.readLine().also { line = it }) != null) {
                json.append(line)
            }
        }
        val obj = JSONObject(json.toString())
        val updatesList: JSONArray = obj.getJSONArray("response")
        for (i in 0 until updatesList.length()) {
            if (updatesList.isNull(i)) {
                continue
            }
            try {
                val update: UpdateInfo = parseJsonUpdate(updatesList.getJSONObject(i))
                if (!compatibleOnly || isCompatible(update)) {
                    updates.add(update)
                } else {
                    Log.d(TAG, "Ignoring incompatible update " + update.name)
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Could not parse update object, index=$i", e)
            }
        }

        return updates
    }

    @JvmStatic
    fun getServerURL(context: Context): String {
        val buildVersion: String = SystemProperties.get(Constants.PROP_BUILD_VERSION)
        val device: String = SystemProperties.get(Constants.PROP_DEVICE)
        val serverUrl: String = context.getString(R.string.updater_server_url)

        return serverUrl.replace("{version}", buildVersion).replace("{device}", device)
    }

    @JvmStatic
    fun getChangelogURL(context: Context): String {
        val buildVersion: String = SystemProperties.get(Constants.PROP_BUILD_VERSION)
        val device: String = SystemProperties.get(Constants.PROP_DEVICE)
        val changelogUrl: String = context.getString(R.string.changelog_url)

        return changelogUrl.replace("{version}", buildVersion).replace("{device}", device)
    }

    @JvmStatic
    fun getCertifiedPropsURL(context: Context): String {
        val buildVersion: String = SystemProperties.get(Constants.PROP_BUILD_VERSION)
        val certifiedPropsUrl: String = context.getString(R.string.certified_prop_json_url)

        return certifiedPropsUrl.replace("{version}", buildVersion)
    }

    @JvmStatic
    fun triggerUpdate(
        context: Context,
        downloadId: String?,
    ) {
        val intent = Intent(context, UpdaterService::class.java)
        intent.setAction(UpdaterService.ACTION_INSTALL_UPDATE)
        intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId)
        context.startService(intent)
    }

    @JvmStatic
    fun isNetworkAvailable(context: Context): Boolean {
        val cm: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)!!
        val activeNetwork: Network? = cm.activeNetwork
        val networkCapabilities: NetworkCapabilities? = cm.getNetworkCapabilities(activeNetwork)
        if (networkCapabilities != null &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        return false
    }

    /**
     * Compares two json formatted updates list files
     *
     * @param oldJson old update list
     * @param newJson new update list
     * @return true if newJson has at least a compatible update not available in oldJson
     */
    @JvmStatic
    @Throws(IOException::class, JSONException::class)
    fun checkForNewUpdates(
        oldJson: File?,
        newJson: File?,
    ): Boolean {
        val oldList: List<UpdateInfo> = parseJson(oldJson, true)
        val newList: List<UpdateInfo> = parseJson(newJson, true)

        val oldIds: MutableSet<String> = HashSet()
        for (update: UpdateInfo in oldList) {
            oldIds.add(update.downloadId)
        }
        // In case of no new updates, the old list should
        // have all (if not more) the updates
        for (update: UpdateInfo in newList) {
            if (!oldIds.contains(update.downloadId)) {
                return true
            }
        }

        return false
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    @JvmStatic
    fun getZipEntryOffset(
        zipFile: ZipFile,
        entryPath: String,
    ): Long {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        val fixedHeaderSize = 30
        val zipEntries: Enumeration<out ZipEntry> = zipFile.entries()
        var offset: Long = 0
        while (zipEntries.hasMoreElements()) {
            val entry: ZipEntry = zipEntries.nextElement()
            val n: Int = entry.name.length
            val m: Int = if (entry.extra == null) 0 else entry.extra.size
            val headerSize: Int = fixedHeaderSize + n + m
            offset += headerSize.toLong()
            if (entry.name == entryPath) {
                return offset
            }
            offset += entry.compressedSize
        }
        Log.e(TAG, "Entry $entryPath not found")
        throw IllegalArgumentException("The given entry was not found")
    }

    private fun removeUncryptFiles(downloadPath: File) {
        val uncryptFiles: Array<File>? =
            downloadPath.listFiles { _: File?, name: String ->
                name.endsWith(Constants.UNCRYPT_FILE_EXT)
            }
        if (uncryptFiles == null) {
            return
        }
        for (file: File in uncryptFiles) {
            file.delete()
        }
    }

    /**
     * Cleanup the download directory, which is assumed to be a privileged location the user can't
     * access and that might have stale files. This can happen if the data of the application are
     * wiped.
     */
    @JvmStatic
    fun cleanupDownloadsDir(context: Context) {
        val downloadPath: File = getDownloadPath(context)
        val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        removeUncryptFiles(downloadPath)

        val buildTimestamp: Long = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val prevTimestamp: Long = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0)
        val lastUpdatePath: String? =
            preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null)
        val reinstalling: Boolean = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
        if ((buildTimestamp != prevTimestamp || reinstalling) && lastUpdatePath != null) {
            val lastUpdate = File(lastUpdatePath)
            if (lastUpdate.exists()) {
                lastUpdate.delete()
                // Remove the pref not to delete the file if re-downloaded
                preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply()
            }
        }

        val downloadsCleanupDone = "cleanup_done"
        if (preferences.getBoolean(downloadsCleanupDone, false)) {
            return
        }

        Log.d(TAG, "Cleaning $downloadPath")
        if (!downloadPath.isDirectory) {
            return
        }
        val files: Array<out File> = downloadPath.listFiles() ?: return

        // Ideally the database is empty when we get here
        val dbHelper = UpdatesDbHelper(context)
        val knownPaths: MutableList<String> = ArrayList()
        for (update in dbHelper.updates) {
            knownPaths.add(update.file.absolutePath)
        }
        for (file: File in files) {
            if (!knownPaths.contains(file.absolutePath)) {
                Log.d(TAG, "Deleting " + file.absolutePath)
                //noinspection ResultOfMethodCallIgnored
                file.delete()
            }
        }

        preferences.edit().putBoolean(downloadsCleanupDone, true).apply()
    }

    @JvmStatic
    fun appendSequentialNumber(file: File): File {
        val name: String
        val extension: String
        val extensionPosition: Int = file.name.lastIndexOf(".")
        if (extensionPosition > 0) {
            name = file.name.substring(0, extensionPosition)
            extension = file.name.substring(extensionPosition)
        } else {
            name = file.name
            extension = ""
        }
        val parent: File? = file.parentFile
        for (i in 1 until Int.MAX_VALUE) {
            val newFile = File(parent, "$name-$i$extension")
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw IllegalStateException()
    }

    @JvmStatic
    val isABDevice: Boolean
        get() = SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false)

    private fun isABUpdate(zipFile: ZipFile): Boolean =
        zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
            zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null

    @JvmStatic
    @Throws(IOException::class)
    fun isABUpdate(file: File?): Boolean {
        val zipFile = ZipFile(file)
        val isAB: Boolean = isABUpdate(zipFile)
        zipFile.close()
        return isAB
    }

    @JvmStatic
    fun isEncrypted(
        context: Context,
        file: File?,
    ): Boolean {
        val sm: StorageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.isEncrypted(file)
    }

    @JvmStatic
    fun getLocalVersion(
        context: Context,
        pkgName: String,
    ): Long {
        var version: Long = -1
        try {
            val pkgInfo: PackageInfo =
                context.packageManager.getPackageInfo(pkgName, PackageManager.MATCH_SYSTEM_ONLY)
            version = pkgInfo.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            // Do nothing
        }
        return version
    }

    @JvmStatic
    fun isUpdateCheckEnabled(context: Context): Boolean {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(Constants.PREF_AUTO_UPDATES_CHECK, true)
    }

    val isRecoveryUpdateExecPresent: Boolean
        get() {
            return File(Constants.UPDATE_RECOVERY_EXEC).exists()
        }

    @JvmStatic
    val securityPatch: String?
        // From DeviceInfoUtils.java
        get() {
            var patch: String = Build.VERSION.SECURITY_PATCH
            if ("" != patch) {
                try {
                    val template = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val patchDate: Date? = template.parse(patch)
                    val format: String =
                        DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMMyyyy")
                    patch = DateFormat.format(format, patchDate).toString()
                } catch (e: ParseException) {
                    // broken parse; fall through and use the raw string
                }
                return patch
            } else {
                return null
            }
        }
}
