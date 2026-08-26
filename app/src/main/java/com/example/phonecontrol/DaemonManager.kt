package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.io.File

object DaemonManager {
    private const val DAEMON_NAME = "phone_control_daemon.sh"
    
    fun startDaemon(context: Context) {
        val daemonFile = File(context.filesDir, DAEMON_NAME)
        val prefsPath = "/data/data/${context.packageName}/shared_prefs/prefs.xml"
        val towerPrefsPath = "/data/data/${context.packageName}/shared_prefs/tower_prefs.xml"
        val logPath = "${context.filesDir.absolutePath}/daemon.log"
        
        val script = """
            #!/system/bin/sh
            # Phone Control Native Daemon - Ultra Robust
            
            PREFS="$prefsPath"
            TOWER_PREFS="$towerPrefsPath"
            LOG="$logPath"
            
            echo "Daemon started at ${'$'}(date)" > "${'$'}LOG"
            
                get_pref_bool() {
                    local pfile="${'$'}1"
                    local pkey="${'$'}2"
                    if [ ! -f "${'$'}pfile" ]; then echo 1; return; fi
                    grep "name=\"${'$'}pkey\"" "${'$'}pfile" | grep "value=\"true\"" > /dev/null
                    echo ${'$'}? 
                }

                get_pref_int() {
                    local pfile="${'$'}1"
                    local pkey="${'$'}2"
                    if [ ! -f "${'$'}pfile" ]; then echo "-1"; return; fi
                    grep "name=\"${'$'}pkey\"" "${'$'}pfile" | sed 's/.*value=\"\(.*\)\".*/\1/'
                }

                apply_tower_lock() {
                    # Check if Tower Lock is enabled in global prefs AND is active in tower prefs
                    if [ ${'$'}(get_pref_bool "${'$'}PREFS" "tower_lock_enabled") -eq 0 ]; then
                        if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "is_tower_locked") -eq 0 ]; then
                            pci=${'$'}(get_pref_int "${'$'}TOWER_PREFS" "locked_pci")
                            earfcn=${'$'}(get_pref_int "${'$'}TOWER_PREFS" "locked_earfcn")
                            if [ "${'$'}pci" != "-1" ] && [ "${'$'}earfcn" != "-1" ]; then
                                echo "Re-applying Tower Lock: EARFCN ${'$'}earfcn, PCI ${'$'}pci" >> "${'$'}LOG"
                                echo -e "AT+ECELL=1,${'$'}earfcn,${'$'}pci\r\n" > /dev/radio/pttycmd1
                            fi
                        fi
                    fi
                }

                apply_5g_antisleep() {
                    if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "5g_antisleep_enabled") -eq 0 ]; then
                        # Gentle Wakeup: Tells MTK modem to prioritize 5G availability
                        echo -e "AT+E5GSWITCH=1\r\n" > /dev/radio/pttycmd1
                        echo -e "AT+EPOWERCONF=0\r\n" > /dev/radio/pttycmd1
                    fi
                }


                apply_sensors() {
                    # 1. Any individual sensor block? (Gyro, Mag, Light, Motion)
                    [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_gyro") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_mag") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_light") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_motion") -eq 0 ]
                    indiv_block=${'$'}?
                    
                    # 2. NFC (Independent)
                    if [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_nfc") -eq 0 ]; then
                        svc nfc disable
                    else
                        svc nfc enable
                    fi

                    # 3. Always-OFF Enforcement: If an individual block is active, force privacy ON
                    # If not, we stay out of it to avoid interfering with the app's Screen-OFF firewall
                    if [ ${'$'}indiv_block -eq 0 ]; then
                        settings put global sensor_privacy 1
                        service call sensor_privacy 2 i32 1
                    fi
                }

            last_app=""
            last_screen="on"

            # 1. TCP BBR Persistence
            sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null

            # 2. Automated FSTRIM (Runs once a week)
            TRIM_FILE="/data/local/tmp/last_trim"
            NOW=${'$'}(date +%s)
            LAST=${'$'}(cat "${'$'}TRIM_FILE" 2>/dev/null || echo 0)
            if [ ${'$'}((NOW - LAST)) -gt 604800 ]; then
                echo "Running weekly FSTRIM..." >> "${'$'}LOG"
                fstrim -v /data >> "${'$'}LOG" 2>&1
                echo "${'$'}NOW" > "${'$'}TRIM_FILE"
            fi

            # 3. Main Loop: Handles persistent refresh and AI load monitoring
            while true; do
                # Global Periodic Refresh
                if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "is_tower_locked") -eq 0 ]; then
                     apply_tower_lock
                fi
                apply_5g_antisleep
                apply_sensors "static"
                
                # 4. AI Load Monitoring (CPU Usage check)
                if [ ${'$'}(get_pref_bool "${'$'}PREFS" "selected_mode" | grep -q "rbAutomatic") ]; then
                    # Get 1-min load average or current usage
                    # Faster way: look at first number in /proc/loadavg (multiplied by 100)
                    load_raw=${'$'}(cat /proc/loadavg | cut -d' ' -f1 | sed 's/\.//')
                    # Standardize to 0-100 range (approximate)
                    load_val=${'$'}((load_raw / 4)) # Adjusted for 8 cores
                    [ "${'$'}load_val" -gt 100 ] && load_val=100
                    
                    # Notify App Service to adjust AI profile
                    am start-service -a com.example.phonecontrol.ACTION_AI_TICK --ei load "${'$'}load_val" com.example.phonecontrol/.AutoTweakService >/dev/null 2>&1
                fi

                # Check for SIM/Network resets and re-apply TCP BBR
                sysctl net.ipv4.tcp_congestion_control | grep -v bbr >/dev/null && sysctl -w net.ipv4.tcp_congestion_control=bbr

                # AI Mode Latency Fix: 
                # If Screen is OFF, sleep 120s regardless of mode.
                # If Screen is ON and in Auto mode, check every 5s for speed.
                current_screen=${'$'}(cat /data/local/tmp/pc_screen 2>/dev/null || echo "on")
                
                if [ "${'$'}current_screen" = "off" ]; then
                    sleep 120
                elif [ ${'$'}(get_pref_bool "${'$'}PREFS" "selected_mode" | grep -q "rbAutomatic") ]; then
                    sleep 5
                else
                    sleep 120
                fi
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
