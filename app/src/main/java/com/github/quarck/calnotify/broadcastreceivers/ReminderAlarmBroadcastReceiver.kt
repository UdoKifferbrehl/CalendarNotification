//
//   Calendar Notifications Plus
//   Copyright (C) 2016 Sergey Parshin (s.parshin.sc@gmail.com)
//
//   This program is free software; you can redistribute it and/or modify
//   it under the terms of the GNU General Public License as published by
//   the Free Software Foundation; either version 3 of the License, or
//   (at your option) any later version.
//
//   This program is distributed in the hope that it will be useful,
//   but WITHOUT ANY WARRANTY; without even the implied warranty of
//   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//   GNU General Public License for more details.
//
//   You should have received a copy of the GNU General Public License
//   along with this program; if not, write to the Free Software Foundation,
//   Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
//

package com.github.quarck.calnotify.broadcastreceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.github.quarck.calnotify.Consts
import com.github.quarck.calnotify.Settings
import com.github.quarck.calnotify.app.ApplicationController
import com.github.quarck.calnotify.globalState
import com.github.quarck.calnotify.logs.Logger
import com.github.quarck.calnotify.quiethours.QuietHoursManager
import com.github.quarck.calnotify.ui.MainActivity
import com.github.quarck.calnotify.utils.alarmManager
import com.github.quarck.calnotify.utils.powerManager
import com.github.quarck.calnotify.utils.setExactAndAlarm
import com.github.quarck.calnotify.utils.wakeLocked

open class ReminderAlarmGenericBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {

        logger.debug("Alarm received")

        if (context == null || intent == null) {
            return;
        }

        context.globalState.lastTimerBroadcastReceived = System.currentTimeMillis()

        wakeLocked(context.powerManager, PowerManager.PARTIAL_WAKE_LOCK, Consts.REMINDER_WAKE_LOCK_NAME) {

            if (!ApplicationController.hasActiveEvents(context)) {
                logger.info("Reminder broadcast alarm received - no active events to remind, returning")
                return@wakeLocked
            }

            val settings = Settings(context)

            val reminderInterval = settings.remindersIntervalMillis
            val currentTime = System.currentTimeMillis()

            val silentUntil = QuietHoursManager.getSilentUntil(settings)

            var nextFireAt = 0L
            var shouldFire = false

            if (settings.quietHoursOneTimeReminderEnabled) {

                if (silentUntil == 0L) {
                    logger.info("One-shot enabled, not in quiet hours, firing")

                    shouldFire = true

                    // Check if regular reminders are enabled and schedule reminder if necessary
                    if (settings.remindersEnabled) {
                        nextFireAt = currentTime + reminderInterval
                        logger.info("Regular reminders enabled, arming next fire at $nextFireAt")
                    }

                } else {
                    nextFireAt = silentUntil
                    logger.info("One-shot enabled, inside quiet hours, postpone until $silentUntil")
                }

            } else if (settings.remindersEnabled) {

                val lastFireTime = Math.max( context.globalState.notificationLastFireTime,
                        context.globalState.reminderLastFireTime)

                val sinceLastFire = currentTime - lastFireTime;

                val numRemindersFired = context.globalState.numRemindersFired
                val maxFires = settings.maxNumberOfReminders

                logger.info("Reminders are enabled, lastFire=$lastFireTime, sinceLastFire=$sinceLastFire, numFired=$numRemindersFired, maxFires=$maxFires")

                if (maxFires == 0 || numRemindersFired <= maxFires) {

                    if (silentUntil != 0L) {
                        logger.info("Reminder postponed until $silentUntil due to quiet hours");
                        nextFireAt = silentUntil

                    } else if (sinceLastFire < reminderInterval - Consts.ALARM_THRESHOLD)  {
                        // Schedule actual time to fire based on how long ago we have fired
                        val leftMillis = reminderInterval - sinceLastFire;
                        nextFireAt = currentTime + leftMillis

                        logger.info("Early alarm: since last: ${sinceLastFire}, interval: ${reminderInterval}, thr: ${Consts.ALARM_THRESHOLD}, left: ${leftMillis}, moving alarm to $nextFireAt");
                    } else {
                        nextFireAt = currentTime + reminderInterval
                        shouldFire = true

                        logger.info("Good to fire, since last: ${sinceLastFire}, interval: ${reminderInterval}, next fire expected at $nextFireAt")

                        if ((sinceLastFire > reminderInterval + Consts.ALARM_THRESHOLD) && (lastFireTime > 0L)) {
                            logger.error("WARNING: timer delay detected, expected to receive timers with interval " +
                                    "$reminderInterval ms, but last fire was seen $sinceLastFire ms ago, " +
                                    "lastFire=$lastFireTime (last reminder at ${context.globalState.reminderLastFireTime}, " +
                                    "last event at ${context.globalState.notificationLastFireTime})")

                            ApplicationController.onReminderAlarmLate(context, sinceLastFire, reminderInterval, lastFireTime)
                        }
                    }
                } else {
                    logger.info("Exceeded max fires $maxFires, fired $numRemindersFired times")
                }
            } else {
                logger.info("Reminders are disabled")
            }

            if (nextFireAt != 0L) {
                context.alarmManager.setExactAndAlarm(
                        context,
                        Settings(context),
                        nextFireAt,
                        ReminderAlarmBroadcastReceiver::class.java, // ignored on KitKat and below
                        ReminderExactAlarmBroadcastReceiver::class.java,
                        MainActivity::class.java,
                        logger)
            }

            if (shouldFire)
                fireReminder(context, currentTime, settings)
        }
    }

    private fun fireReminder(context: Context, currentTime: Long, settings: Settings) {

        logger.info("Firing reminder, current time ${System.currentTimeMillis()}")

        ApplicationController.fireEventReminder(context);

        // following will actually write xml to file, so check if it is 'true' at the moment
        // before writing 'false' and so wasting flash memory cycles.
        if (settings.quietHoursOneTimeReminderEnabled)
            settings.quietHoursOneTimeReminderEnabled = false;
        else
            context.globalState.numRemindersFired ++;

        context.globalState.reminderLastFireTime = currentTime
    }

    companion object {
        private val logger = Logger("BroadcastReceiverReminderAlarm");
    }
}

class ReminderAlarmBroadcastReceiver : ReminderAlarmGenericBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) =  super.onReceive(context, intent)
}

class ReminderExactAlarmBroadcastReceiver: ReminderAlarmGenericBroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) =  super.onReceive(context, intent)
}
