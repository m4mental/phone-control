package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.io.File

object DaemonManager {
    const val PREF_TOWER_LOCK_ENABLED = "tower_lock_enabled"
    const val PREF_BLOCK_GYRO = "block_gyro"
    const val PREF_IS_TOWER_LOCKED = "is_tower_locked"

    private const val DAEMON_NAME = "phone_control_daemon.sh"
    private const val PID_FILE_NAME = "daemon.pid"
    private var cachedPidFile: File? = null

    fun startDaemon(context: Context) {
        val daemonFile = File(context.filesDir, DAEMON_NAME)
        val pidFile = File(context.filesDir, PID_FILE_NAME)
        cachedPidFile = pidFile

        val prefsPath = "/data/data/${context.packageName}/shared_prefs/prefs.xml"
        val towerPrefsPath = "/data/data/${context.packageName}/shared_prefs/tower_prefs.xml"
        val logPath = "${context.filesDir.absolutePath}/daemon.log"
        val trimFilePath = File(context.filesDir, "last_trim").absolutePath
        val nightFilePath = File(context.filesDir, "last_night_opt").absolutePath
        val pidFilePath = pidFile.absolutePath

        val script = """
            #!/system/bin/sh
            # Phone Control Event-Driven Native Daemon
            
            PID_FILE="$pidFilePath"
            echo "$$" > "${'$'}PID_FILE"
            trap 'rm -f "${'$'}PID_FILE"; exit 0' EXIT TERM INT HUP

            PREFS="$prefsPath"
            TOWER_PREFS="$towerPrefsPath"
            LOG="$logPath"
            TRIM_FILE="$trimFilePath"
            NIGHT_FILE="$nightFilePath"
            
            echo "Event-Driven Daemon started at ${'$'}(date)" > "${'$'}LOG"
            
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
                if [ ${'$'}(get_pref_bool "${'$'}PREFS" "$PREF_TOWER_LOCK_ENABLED") -eq 0 ]; then
                    if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "$PREF_IS_TOWER_LOCKED") -eq 0 ]; then
                        pci=${'$'}(get_pref_int "${'$'}TOWER_PREFS" "locked_pci")
                        earfcn=${'$'}(get_pref_int "${'$'}TOWER_PREFS" "locked_earfcn")
                        if [ "${'$'}pci" != "-1" ] && [ "${'$'}earfcn" != "-1" ]; then
                            echo "Applying Tower Lock: EARFCN ${'$'}earfcn, PCI ${'$'}pci" >> "${'$'}LOG"
                            echo -e "AT+ECELL=1,${'$'}earfcn,${'$'}pci\r\n" > /dev/radio/pttycmd1
                        fi
                    fi
                fi
            }

            apply_5g_antisleep() {
                if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "5g_antisleep_enabled") -eq 0 ]; then
                    echo -e "AT+E5GSWITCH=1\r\n" > /dev/radio/pttycmd1
                    echo -e "AT+EPOWERCONF=0\r\n" > /dev/radio/pttycmd1
                fi
            }

            apply_sensors() {
                [ ${'$'}(get_pref_bool "${'$'}PREFS" "$PREF_BLOCK_GYRO") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_mag") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_light") -eq 0 ] || [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_motion") -eq 0 ]
                indiv_block=${'$'}?
                
                if [ ${'$'}(get_pref_bool "${'$'}PREFS" "block_nfc") -eq 0 ]; then
                    svc nfc disable
                else
                    svc nfc enable
                fi

                if [ ${'$'}indiv_block -eq 0 ]; then
                    settings put global sensor_privacy 1
                fi
            }

            # 1. TCP BBR Persistence
            sysctl -w net.ipv4.tcp_congestion_control=bbr 2>/dev/null

            # 2. Automated FSTRIM (Runs once a week)
            NOW=${'$'}(date +%s)
            LAST=${'$'}(cat "${'$'}TRIM_FILE" 2>/dev/null || echo 0)
            if [ ${'$'}((NOW - LAST)) -gt 604800 ]; then
                echo "Running weekly FSTRIM..." >> "${'$'}LOG"
                (sm fstrim || fstrim -v /data) >> "${'$'}LOG" 2>&1
                echo "${'$'}NOW" > "${'$'}TRIM_FILE"
            fi

            # Initial apply
            if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "$PREF_IS_TOWER_LOCKED") -eq 0 ]; then
                 apply_tower_lock
            fi
            apply_5g_antisleep
            apply_sensors

            # 3. Clean Event Maintenance Loop (Zero aggressive wakeups)
            while true; do
                sleep 60
                
                if [ ${'$'}(get_pref_bool "${'$'}TOWER_PREFS" "$PREF_IS_TOWER_LOCKED") -eq 0 ]; then
                     apply_tower_lock
                fi
                apply_5g_antisleep
                
                # Check for SIM/Network resets and re-apply TCP BBR
                sysctl net.ipv4.tcp_congestion_control | grep -v bbr >/dev/null && sysctl -w net.ipv4.tcp_congestion_control=bbr

                # 4. Automated Night Deep Clean (Runs once per day at 03:00 AM)
                CUR_HR=${'$'}(date +%H)
                if [ "${'$'}CUR_HR" = "03" ]; then
                    TODAY=${'$'}(date +%Y%m%d)
                    LAST_OPT=${'$'}(cat "${'$'}NIGHT_FILE" 2>/dev/null)
                    if [ "${'$'}TODAY" != "${'$'}LAST_OPT" ]; then
                        echo "${'$'}TODAY" > "${'$'}NIGHT_FILE"
                        echo "Running daily 03:00 AM Night Maintenance..." >> "${'$'}LOG"
                        (sm fstrim || fstrim -v /data) >> "${'$'}LOG" 2>&1
                        sync
                        echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
                    fi
                fi
            done
        """.trimIndent()

        try {
            stopDaemon(context)
            daemonFile.writeText(script)
            val chmodRes = ShellUtils.runAsRoot("chmod 700 ${ShellUtils.shellQuote(daemonFile.absolutePath)}")
            if (chmodRes.exitCode != 0) {
                Log.e("DaemonManager", "chmod 700 failed with exit code ${chmodRes.exitCode}: ${chmodRes.output}")
                return
            }
            val launchRes = ShellUtils.runAsRoot("sh -c '(${ShellUtils.shellQuote(daemonFile.absolutePath)} >/dev/null 2>&1 &)'")
            if (launchRes.exitCode != 0) {
                Log.e("DaemonManager", "Daemon launch failed with exit code ${launchRes.exitCode}: ${launchRes.output}")
                return
            }
            Log.d("DaemonManager", "Event-Driven Daemon started successfully")
        } catch (e: Exception) {
            Log.e("DaemonManager", "Error starting daemon", e)
        }
    }

    fun stopDaemon(context: Context? = null) {
        val pidFile = (context?.filesDir?.let { File(it, PID_FILE_NAME) })
            ?: cachedPidFile
            ?: File("/data/data/com.example.phonecontrol/files", PID_FILE_NAME)

        try {
            val pidPath = pidFile.absolutePath
            val catRes = ShellUtils.runAsRoot("if [ -f ${ShellUtils.shellQuote(pidPath)} ]; then cat ${ShellUtils.shellQuote(pidPath)}; fi")
            val pid = catRes.output.trim()
            if (ShellUtils.isValidPid(pid)) {
                val cmdlineRes = ShellUtils.runAsRoot("if [ -f /proc/$pid/cmdline ]; then cat /proc/$pid/cmdline; fi")
                if (cmdlineRes.output.contains(DAEMON_NAME)) {
                    ShellUtils.runAsRoot("kill -9 $pid")
                } else {
                    Log.w("DaemonManager", "PID $pid does not belong to $DAEMON_NAME; skipping kill")
                }
                ShellUtils.runAsRoot("rm -f ${ShellUtils.shellQuote(pidPath)}")
                try { pidFile.delete() } catch (_: Exception) {}
            } else {
                // Fallback to stop legacy daemon by matching phone_control_daemon.sh when no PID file exists
                ShellUtils.runAsRoot("pkill -f $DAEMON_NAME 2>/dev/null")
            }
        } catch (e: Exception) {
            Log.e("DaemonManager", "Error stopping daemon", e)
        }
    }
}
