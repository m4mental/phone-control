package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.io.File

object DaemonManager {
    private const val DAEMON_NAME = "phone_control_daemon.sh"
    
    fun startDaemon(context: Context) {
        val daemonFile = File(context.filesDir, DAEMON_NAME)
        val prefsPath = "/data/data/${context.packageName}/shared_prefs/prefs.xml"
        val logPath = "${context.filesDir.absolutePath}/daemon.log"
        
        val script = """
            #!/system/bin/sh
            # Phone Control Native Daemon - Ultra Robust
            
            PREFS="$prefsPath"
            LOG="$logPath"
            
            echo "Daemon started at ${'$'}(date)" > "${'$'}LOG"
            
                get_pref_bool() {
                    if [ ! -f "${'$'}PREFS" ]; then echo 1; return; fi
                    grep "name=\"${'$'}1\"" "${'$'}PREFS" | grep "value=\"true\"" > /dev/null
                    echo ${'$'}? 
                }

                apply_sensors() {
                    # 1. Any individual sensor block?
                    [ ${'$'}(get_pref_bool "block_gyro") -eq 0 ] || [ ${'$'}(get_pref_bool "block_mag") -eq 0 ] || [ ${'$'}(get_pref_bool "block_light") -eq 0 ] || [ ${'$'}(get_pref_bool "block_motion") -eq 0 ]
                    indiv_block=${'$'}?
                    
                    # 2. Screen Off Firewall?
                    [ ${'$'}(get_pref_bool "sensor_firewall_enabled") -eq 0 ]
                    firewall=${'$'}?
                    
                    # 3. NFC (Independent)
                    if [ ${'$'}(get_pref_bool "block_nfc") -eq 0 ]; then
                        svc nfc disable
                    else
                        svc nfc enable
                    fi

                    if [ ${'$'}indiv_block -eq 0 ]; then
                        settings put global sensor_privacy 1
                        service call sensor_privacy 2 i32 1
                    elif [ "${'$'}1" = "off" ] && [ ${'$'}firewall -eq 0 ]; then
                        settings put global sensor_privacy 1
                        service call sensor_privacy 2 i32 1
                    else
                        settings put global sensor_privacy 0
                        service call sensor_privacy 2 i32 0
                    fi
                }

                last_app=""
            last_screen="on"

            while true; do
                # 1. Check Screen State (More compatible method)
                pstate=${'$'}(dumpsys power | grep "Display Power: state=" | cut -d "=" -f2)
                [ -z "${'$'}pstate" ] && pstate="ON" # Fallback

                if [ "${'$'}pstate" = "OFF" ] || [ "${'$'}pstate" = "DisplayOff" ]; then
                    if [ "${'$'}last_screen" != "off" ]; then
                        echo "Screen Off Triggered" >> "${'$'}LOG"
                        # Master Automation check
                        if [ ${'$'}(get_pref_bool "automation_enabled") -eq 0 ]; then
                            # Sensor logic
                            apply_sensors "off"

                            # GPS Saver logic: Remember state before turning off
                            if [ ${'$'}(get_pref_bool "gps_auto_saver_enabled") -eq 0 ]; then
                                current_gps=${'$'}(settings get secure location_mode)
                                if [ "${'$'}current_gps" != "0" ]; then
                                    echo "1" > "/data/local/tmp/gps_was_on"
                                    settings put secure location_mode 0
                                else
                                    echo "0" > "/data/local/tmp/gps_was_on"
                                fi
                            fi

                            [ ${'$'}(get_pref_bool "batt_power_save_screen_off") -eq 0 ] && cmd battery-saver set-enabled true
                            
                            # Standby Guard
                            if [ ${'$'}(get_pref_bool "standby_guard_enabled") -eq 0 ]; then
                                pm list packages -3 | cut -d ':' -f2 | while read pkg; do
                                    am set-standby-bucket "${'$'}pkg" restricted 2>/dev/null
                                done
                            fi
                        fi
                        last_screen="off"
                    fi
                    sleep 5
                else
                    if [ "${'$'}last_screen" != "on" ]; then
                        echo "Screen On Triggered" >> "${'$'}LOG"
                        if [ ${'$'}(get_pref_bool "automation_enabled") -eq 0 ]; then
                            # Sensor logic
                            apply_sensors "on"

                            # GPS Saver logic: Restore only if it was ON before screen-off
                            if [ ${'$'}(get_pref_bool "gps_auto_saver_enabled") -eq 0 ]; then
                                if [ -f "/data/local/tmp/gps_was_on" ] && [ "${'$'}(cat /data/local/tmp/gps_was_on)" = "1" ]; then
                                    settings put secure location_mode 3
                                fi
                                rm -f "/data/local/tmp/gps_was_on"
                            fi
                        fi
                        cmd battery-saver set-enabled false
                        last_screen="on"
                    fi
                fi

                sleep 5
            done
        """.trimIndent()

        try {
            daemonFile.writeText(script)
            ShellUtils.runAsRoot("chmod 777 ${daemonFile.absolutePath}")
            ShellUtils.runAsRoot("pkill -f $DAEMON_NAME")
            // Double-fork background start (Highest compatibility)
            ShellUtils.runAsRoot("sh -c '(${daemonFile.absolutePath} >/dev/null 2>&1 &)'")
            Log.d("DaemonManager", "Daemon started via double-fork")
        } catch (e: Exception) {
            Log.e("DaemonManager", "Error starting daemon", e)
        }
    }

    fun stopDaemon() {
        ShellUtils.runAsRoot("pkill -f $DAEMON_NAME")
    }
}
