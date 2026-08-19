package com.example.phonecontrol

import android.content.Context
import android.util.Log
import java.io.File

object DaemonManager {
    private const val DAEMON_NAME = "phone_control_daemon.sh"
    
    /**
     * Deploys and starts the native daemon script.
     */
    fun startDaemon(context: Context) {
        val daemonFile = File(context.filesDir, DAEMON_NAME)
        
        val script = """
            #!/system/bin/sh
            # Phone Control Native Daemon - Ultra Lightweight
            
            BASE_DIR="${context.filesDir.absolutePath}"
            STATE_FILE="${'$'}BASE_DIR/daemon_state"
            LOG_FILE="${'$'}BASE_DIR/daemon.log"
            
            last_app=""
            last_screen="on"
            last_load=0
            
            echo "Daemon started at ${'$'}(date)" > "${'$'}LOG_FILE"
            
            while true; do
                # 1. Check Screen State
                screen_state=${'$'}(dumpsys display | grep "mScreenState" | head -n 1 | cut -d "=" -f2)
                
                if [ "${'$'}screen_state" = "OFF" ]; then
                    if [ "${'$'}last_screen" != "off" ]; then
                        am broadcast -a com.example.phonecontrol.ACTION_STATE_CHANGED --es "event" "screen_off"
                        last_screen="off"
                    fi
                    sleep 10
                    continue
                else
                    if [ "${'$'}last_screen" != "on" ]; then
                        am broadcast -a com.example.phonecontrol.ACTION_STATE_CHANGED --es "event" "screen_on"
                        last_screen="on"
                    fi
                fi

                # 2. Monitor Foreground App
                top_app=${'$'}(dumpsys window | grep mCurrentFocus | cut -d '/' -f1 | rev | cut -d ' ' -f1 | rev)
                
                if [ "${'$'}top_app" != "${'$'}last_app" ]; then
                    am broadcast -a com.example.phonecontrol.ACTION_STATE_CHANGED --es "event" "app_change" --es "pkg" "${'$'}top_app"
                    last_app="${'$'}top_app"
                fi

                # 3. Dynamic CPU Load (Every 4 seconds to save battery)
                if [ $(( $(date +%s) % 4 )) -eq 0 ]; then
                    # Get CPU Load from /proc/stat
                    load=${'$'}(top -n 1 -b -m 1 | grep "CPU" | head -n 1 | awk '{print ${'$'}2}' | cut -d '%' -f1 | cut -d '.' -f1)
                    
                    # Only broadcast if load changes significantly (> 15%) or if it crosses thresholds
                    if [ "${'$'}load" -gt 60 ] || [ "${'$'}load" -lt 25 ]; then
                         am broadcast -a com.example.phonecontrol.ACTION_STATE_CHANGED --es "event" "load_change" --ei "load" "${'$'}load"
                    fi
                fi
                
                sleep 2
            done
        """.trimIndent()

        try {
            daemonFile.writeText(script)
            ShellUtils.runAsRoot("chmod 777 ${daemonFile.absolutePath}")
            
            // Kill existing daemon before starting new one
            ShellUtils.runAsRoot("pkill -f $DAEMON_NAME")
            
            // Start in background using nohup to keep it alive even if shell closes
            ShellUtils.runAsRoot("nohup sh ${daemonFile.absolutePath} > /dev/null 2>&1 &")
            Log.d("DaemonManager", "Native Daemon Started Successfully")
        } catch (e: Exception) {
            Log.e("DaemonManager", "Failed to start daemon", e)
        }
    }

    fun stopDaemon() {
        ShellUtils.runAsRoot("pkill -f $DAEMON_NAME")
    }
}
