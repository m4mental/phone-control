# Phone Control - The Ultimate Root Utility (Nothing Phone 2a Optimized)

**Phone Control** is a professional-grade, root-level optimization suite designed to maximize performance, battery life, and stability. While it works on most rooted devices, it is specifically tuned for the **Nothing Phone (2a)** and its **MediaTek Dimensity 7200 Pro** chipset.

## 🚀 Key Features

### 1. Intelligent AI Mode Control
- **Manual Profiles:** Switch between **Power Saver** (Efficiency), **Balanced** (Daily), and **Performance** (Cheetah).
- **Auto-AI Engine:** Real-time load sensing that dynamically adjusts CPU/GPU.
  - **AI: Sleeping:** Parks big cores for zero idle drain.
  - **AI: Daily Fluent:** Perfect for chatting and social media.
  - **AI: Multi-Boost:** Instant power for reels and app switching.
  - **AI: Extreme:** Unlocked potential for gaming and heavy tasks.

### 2. Game Turbo Suite
- **Auto-Detection:** Automatically triggers performance tweaks when you launch games like BGMI or Genshin Impact.
- **Stable Ping:** Uses `iptables` Packet Guard to prioritize gaming data over background noise.
- **Thermal Unlock:** Optional thermal limit removal for consistent FPS.

### 3. Advanced RAM & Storage Manager
- **ZRAM Engine:** High-speed compressed physical RAM (4GB-8GB) for 50x faster multitasking than traditional Virtual RAM.
- **UFS Boost:** Weekly automated **FSTRIM** and mq-deadline I/O tuning to keep storage speeds like new.
- **App Freezer:** Hibernates background apps with "Auto-Freeze on Screen OFF" logic.

### 4. Network & Connectivity Pro
- **Smart Data Switcher:** Event-driven (Zero Polling) automation that disables mobile data on WiFi and restores it when out.
- **TCP BBR:** Forces Google's BBR congestion control for maximum internet throughput.
- **5G Anti-Sleep:** Prevents the modem from dropping to 4G during inactivity (Ideal for Hotspot).
- **Home Tower Lock:** Hard-lock specific PCI/EARFCN for indoor signal stability.

### 5. Battery Control & Super Doze
- **Super Doze Mode:** Kernel-level deep sleep that achieves near **0% battery drop overnight**.
- **Charging Protection:** Set max charge limits (e.g., 80%) and enable **Direct Power Bypass** to reduce heat while gaming.
- **Sensor Firewall:** Blocks Gyro, Mag, and Light sensors when the screen is locked to eliminate standby drain.

### 6. Expert Tools
- **Root Terminal:** A secured, hacker-style console with command history, dangerous command safeguards, and UFS/Network debug chips.
- **Bloatware Remover:** Force-disable pre-installed system junk.
- **App & Data Vault:** ⚠️ **STILL UNDER DEVELOPMENT** - This feature is currently unstable and may cause crashes during the backup/restore process. Use only for testing.

---

## 🛠 Technical Architecture

- **Native Shell Daemon:** A persistent C-style shell daemon (`phone_control_daemon.sh`) that manages low-level hardware triggers.
- **Event-Driven Logic:** Uses Android OS Signals (Broadcasts) instead of Polling loops to prevent crashes and save battery.
- **MediaTek Tuning:** Specific core-indexing and cluster parking logic for Dimensity architecture.
- **Biometric Security:** Biometric locks on sensitive areas like the Root Terminal and Data Vault.

## ⚠️ Requirements
- **Root Access:** Magisk or KernelSU required.
- **Nothing Phone (2a):** Highly recommended for full feature compatibility and MTK tweaks.
- **Android 12+:** Optimized for modern Android versions (up to Android 15/16).

## 🛡 Disclaimer
This app modifies system parameters. Use it at your own risk. The developer is not responsible for any data loss or hardware issues. Always use the **Kill Switch (Revert All)** before uninstalling the app.

---
*Developed with ❤️ for the Nothing Community.*
