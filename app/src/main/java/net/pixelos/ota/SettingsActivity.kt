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
package net.pixelos.ota

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.SystemProperties
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pixelos.ota.controller.UpdaterController
import net.pixelos.ota.download.Downloader
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.misc.Utils
import net.pixelos.ota.misc.Utils.getLocalVersion
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        setSupportActionBar(toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true) }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, RootSettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    abstract class SettingsFragment(
        @XmlRes private val preferencesResId: Int,
    ) : PreferenceFragmentCompat() {
        @CallSuper
        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            setPreferencesFromResource(preferencesResId, rootKey)
        }
    }

    class RootSettingsFragment : SettingsFragment(R.xml.preferences) {
        private var updaterController: UpdaterController = UpdaterController.getInstance(context)

        private val autoUpdateCheck by lazy {
            findPreference<SwitchPreferenceCompat>(Constants.PREF_AUTO_UPDATES_CHECK)!!
        }
        private val abPerfMode by lazy {
            findPreference<SwitchPreferenceCompat>(Constants.PREF_AB_PERF_MODE)
        }
        private val updateRecovery by lazy {
            findPreference<SwitchPreferenceCompat>(Constants.PREF_UPDATE_RECOVERY)!!
        }

        private val sharedPreference by lazy {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
        }
        private val generalCategory by lazy {
            preferenceScreen.findPreference<PreferenceCategory>("general")!!
        }
        private val checkForCertifiedProps by lazy {
            findPreference<Preference>(Constants.PREF_CHECK_FOR_CERTIFIED_PROPS)!!
        }
        private val certifiedPropStatus by lazy {
            findPreference<Preference>(Constants.PREF_CERTIFIED_PROP_STATUS)!!
        }

        private val certifiedPropOverlayPkgName = "co.aospa.android.certifiedprops.overlay"

        override fun onCreatePreferences(
            savedInstanceState: Bundle?,
            rootKey: String?,
        ) {
            super.onCreatePreferences(savedInstanceState, rootKey)

            abPerfMode?.let {
                if (supportsPerfMode()) {
                    it.isChecked = sharedPreference.getBoolean(Constants.PREF_AB_PERF_MODE, false)
                } else {
                    generalCategory.removePreference(it)
                }
                it.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        sharedPreference
                            .edit()
                            .putBoolean(Constants.PREF_AB_PERF_MODE, newValue as Boolean)
                            .apply()
                        updaterController.setPerformanceMode(isPerfModeEnabled(newValue))

                        true
                    }
            }

            updateRecovery.let {
                if (resources.getBoolean(R.bool.config_hideRecoveryUpdate)) {
                    // Hide the update feature if explicitly requested.
                    // Might be the case of A-only devices using prebuilt vendor images.
                    generalCategory.removePreference(it)
                } else if (Utils.isRecoveryUpdateExecPresent) {
                    it.isChecked =
                        SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false)
                } else {
                    // There is no recovery updater script in the device, so the feature is considered
                    // forcefully enabled, just to avoid users to be confused and complain that
                    // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
                    it.isChecked = true
                    generalCategory.removePreference(it)
                }

                it.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        SystemProperties.set(
                            Constants.UPDATE_RECOVERY_PROPERTY,
                            newValue.toString(),
                        )
                        true
                    }
            }

            autoUpdateCheck.let {
                it.isChecked = sharedPreference.getBoolean(Constants.PREF_AUTO_UPDATES_CHECK, false)
                it.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, newValue ->
                        sharedPreference
                            .edit()
                            .putBoolean(Constants.PREF_AUTO_UPDATES_CHECK, (newValue as Boolean))
                            .apply()
                        if (Utils.isUpdateCheckEnabled(requireContext())) {
                            UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(requireContext())
                        } else {
                            UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(requireContext())
                            UpdatesCheckReceiver.cancelUpdatesCheck(requireContext())
                        }
                        true
                    }
            }

            setupPreferenceAction(Action.CHECK_UPDATES)
            updateCertifiedPropsStatus(-1)
        }

        private fun setupPreferenceAction(action: Action) {
            when (action) {
                Action.CHECK_UPDATES -> {
                    checkForCertifiedProps.apply {
                        layoutResource = R.layout.certified_props_check
                        onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                CoroutineScope(Dispatchers.Main).launch {
                                    checkForCertifiedPropsUpdate()
                                }
                                true
                            }
                    }
                }

                Action.DOWNLOAD_AND_INSTALL -> {
                    checkForCertifiedProps.apply {
                        layoutResource = R.layout.certified_props_download_install
                        onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                CoroutineScope(Dispatchers.Main).launch {
                                    updateCertifiedProps()
                                }
                                true
                            }
                    }
                }
            }
        }

        private enum class Action {
            CHECK_UPDATES,
            DOWNLOAD_AND_INSTALL,
        }

        private suspend fun checkForCertifiedPropsUpdate() {
            certifiedPropStatus.summary = getString(R.string.certified_prop_checking)
            checkForCertifiedProps.isEnabled = false

            val jsonStr = Downloader.asString(Utils.getCertifiedPropsURL(requireContext()))
            withContext(Dispatchers.Main) {
                if (jsonStr.isNullOrEmpty()) {
                    showToast(R.string.certified_prop_download_failed)
                    checkForCertifiedProps.isEnabled = true
                    updateCertifiedPropsStatus(-1)
                    return@withContext
                }

                try {
                    val obj = JSONObject(jsonStr)
                    if (obj.has("version")) {
                        val version: Int = obj.getInt("version")
                        updateCertifiedPropsStatus(version.toLong())
                    }
                } catch (e: Exception) {
                    showToast(R.string.certified_prop_download_failed)
                } finally {
                    checkForCertifiedProps.isEnabled = true
                }
            }
        }

        private fun showToast(stringId: Int) {
            Toast.makeText(context, stringId, Toast.LENGTH_LONG).show()
        }

        private suspend fun updateCertifiedProps() {
            val path =
                File(requireContext().getExternalFilesDir(null), "prop.apk").absolutePath
            val url = Utils.getCertifiedPropsURL(requireContext()).replace("json", "apk")
            var success: Boolean

            try {
                withContext(Dispatchers.IO) {
                    success = Downloader.downloadApk(path, url)
                }
                if (!success) {
                    showToast(R.string.certified_prop_download_failed)
                } else {
                    if (installApk(path)) {
                        showToast(R.string.certified_prop_install_success)
                    } else {
                        showToast(R.string.certified_prop_install_failed)
                    }
                }
            } catch (e: Exception) {
                showToast(R.string.snack_download_failed)
            } finally {
                checkForCertifiedProps.isEnabled = true
            }
        }

        private fun installApk(path: String): Boolean =
            try {
                val packageInstaller = requireContext().packageManager.packageInstaller
                val sessionParams =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

                val sessionId = packageInstaller.createSession(sessionParams)
                val session = packageInstaller.openSession(sessionId)

                FileInputStream(File(path)).use { inputStream ->
                    session.openWrite("app_install", 0, -1).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        session.fsync(outputStream)
                    }
                }
                val receiverIntent = Intent(context, PackageInstallerStatusReceiver::class.java)
                val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val receiverPendingIntent =
                    PendingIntent.getBroadcast(context, 0, receiverIntent, flags)
                session.commit(receiverPendingIntent.intentSender)

                session.close()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }

        private fun updateCertifiedPropsStatus(remoteVersion: Long) {
            val status = StringBuilder()
            val version: Long = getLocalVersion(requireContext(), certifiedPropOverlayPkgName)

            val unknownStr = resources.getString(R.string.text_download_size_unknown)
            val versionStr: String =
                String.format(
                    Locale.getDefault(),
                    resources.getString(R.string.certified_prop_info),
                    if (version > 0) version else unknownStr,
                )
            val remoteStr: String =
                String.format(
                    Locale.getDefault(),
                    resources.getString(R.string.certified_prop_remote),
                    if (remoteVersion > 0) remoteVersion else unknownStr,
                )

            status.append(versionStr)
            status.append(remoteStr)

            if (remoteVersion > version) setupPreferenceAction(Action.DOWNLOAD_AND_INSTALL)
            certifiedPropStatus.summary = status.toString()
        }

        private fun supportsPerfMode(): Boolean = Utils.isABDevice && resources.getBoolean(R.bool.config_ab_perf_mode)

        private fun isPerfModeEnabled(isEnabled: Boolean): Boolean = supportsPerfMode() && isEnabled
    }
}
