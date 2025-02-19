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

import android.content.Context
import net.pixelos.ota.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object StringGenerator {
    @JvmStatic
    fun getTimeLocalized(
        context: Context,
        unixTimestamp: Long,
    ): String {
        val f = DateFormat.getTimeInstance(DateFormat.SHORT, getCurrentLocale(context)!!)
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun getDateLocalized(
        context: Context,
        dateFormat: Int,
        unixTimestamp: Long,
    ): String {
        val f = DateFormat.getDateInstance(dateFormat, getCurrentLocale(context)!!)
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun getDateLocalizedUTC(
        context: Context,
        dateFormat: Int,
        unixTimestamp: Long,
    ): String {
        val f = DateFormat.getDateInstance(dateFormat, getCurrentLocale(context)!!)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val date = Date(unixTimestamp * 1000)
        return f.format(date)
    }

    @JvmStatic
    fun formatETA(
        context: Context,
        millis: Long,
    ): String {
        val secondInMillis: Long = 1000
        val minuteInMillis = secondInMillis * 60
        val hourInMillis = minuteInMillis * 60
        val res = context.resources
        if (millis >= hourInMillis) {
            val hours = ((millis + 1800000) / hourInMillis).toInt()
            return res.getQuantityString(R.plurals.eta_hours, hours, hours)
        } else if (millis >= minuteInMillis) {
            val minutes = ((millis + 30000) / minuteInMillis).toInt()
            return res.getQuantityString(R.plurals.eta_minutes, minutes, minutes)
        } else {
            val seconds = ((millis + 500) / secondInMillis).toInt()
            return res.getQuantityString(R.plurals.eta_seconds, seconds, seconds)
        }
    }

    private fun getCurrentLocale(context: Context): Locale? =
        context.resources.configuration.locales
            .getFirstMatch(context.resources.assets.locales)
}
