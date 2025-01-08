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

import android.os.Bundle
import android.os.SystemProperties
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
import net.pixelos.ota.controller.UpdaterController
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.misc.Utils

class SettingsActivity : AppCompatActivity(R.layout.activity_settings) {
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable edge-to-edge
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(R.string.menu_preferences)
            setDisplayHomeAsUpEnabled(true)
        }

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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
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
                            .putBoolean(Constants.PREF_AB_PERF_MODE, (newValue as Boolean))
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
                } else if (Utils.isRecoveryUpdateExecPresent()) {
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
                    Preference.OnPreferenceChangeListener { _, _ ->
                        if (Utils.isRecoveryUpdateExecPresent()) {
                            val enableRecoveryUpdate = updateRecovery.isChecked
                            SystemProperties.set(
                                Constants.UPDATE_RECOVERY_PROPERTY,
                                enableRecoveryUpdate.toString()
                            )
                        }
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
        }

        private fun supportsPerfMode(): Boolean {
            return Utils.isABDevice() && resources.getBoolean(R.bool.config_ab_perf_mode)
        }

        private fun isPerfModeEnabled(isEnabled: Boolean): Boolean {
            return supportsPerfMode() && isEnabled
        }
    }
}
