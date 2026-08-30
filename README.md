# 📱 Phone Control — Advanced Android Root Optimization Suite

[![Android](https://img.shields.io/badge/Android-12%20--%2016-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://android.com)
[![Target Device](https://img.shields.io/badge/Target%20Device-Nothing%20Phone%20(2a)-black?style=for-the-badge&logo=nothing&logoColor=white)](https://nothing.tech)
[![Chipset](https://img.shields.io/badge/Chipset-MediaTek%20Dimensity%207200%20Pro-orange?style=for-the-badge)](https://mediatek.com)
[![Root](https://img.shields.io/badge/Root-Magisk%20%7C%20KernelSU%20%7C%20APatch-red?style=for-the-badge&logo=superuser&logoColor=white)](https://github.com/topjohnwu/Magisk)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge)](LICENSE)

> [!CAUTION]
> ### 🛑 TARGET HARDWARE & CHIPSET NOTICE
> **This utility was custom-built, fine-tuned, and tested specifically for the Nothing Phone (2a) powered by the MediaTek Dimensity 7200 Pro chipset.**
> 
> * **Hardware-Specific Features:** Deep kernel-level mechanisms — including MediaTek CPUIdle C-States, MTK PPM core cluster parking, Nothing hardware charging bypass nodes (`/sys/class/power_supply/battery/charging_enabled`), and MTK FPSGO / GED rendering schedulers — are explicitly mapped for this device and chipset.
> * **Other Devices / Chipsets (Snapdragon, Google Tensor, Exynos, Unisoc):** While standard Android framework modules (Force Doze OS Layer, App Standby Buckets Guard, Hardware Sensor Firewall, Google TCP BBR, App Freezer, Resolution & DPI scaling) are universally compatible across ARM64 Android, chipset-specific kernel paths will not function or may cause instability on non-target devices.
> * **ZERO RESPONSIBILITY DISCLAIMER:** If you choose to install or run this utility on any other device or chipset, you do so **STRICTLY AT YOUR OWN RISK**. The developer assumes **ABSOLUTELY NO RESPONSIBILITY** for bootloops, system freezes, kernel crashes, or hardware malfunctions on unsupported hardware.

---

## 📖 Overview

**Phone Control** is a high-performance, modular system control and tuning suite engineered for rooted Android devices. Built with strict event-driven architecture, it delivers fine-grained kernel control, deep battery conservation, intelligent load-balancing AI modes, hardware-level display management, and network packet optimization without background polling overhead.

---

## 📑 Table of Contents
- [Architecture Overview](#-architecture-overview)
- [Core Functional Modules](#-core-functional-modules)
  - [1. Battery & Power Hub](#1--battery--power-hub)
  - [2. Performance & Display Hub](#2--performance--display-hub)
  - [3. Gaming & App Turbo Hub](#3--gaming--app-turbo-hub)
  - [4. Security & Network Hub](#4--security--network-hub)
  - [5. App & Storage Management Hub](#5--app--storage-management-hub)
- [Universal Protected Whitelist System](#-universal-protected-whitelist-system)
- [System Operating Modes & AI Engine](#-system-operating-modes--ai-engine)
- [Master Management & Safe Revert Architecture](#-master-management--safe-revert-architecture)
- [Installation & Requirements](#-installation--requirements)
- [Build Instructions](#-build-instructions)
- [Disclaimer & Safety](#-disclaimer--safety)

---

## 🏛️ Architecture Overview

```
                      ┌──────────────────────────────────────────────┐
                      │             Phone Control UI                 │
                      │   (Material 3 / ViewBinding / Kotlin Flow)   │
                      └──────────────────────┬───────────────────────┘
                                             │
                      ┌──────────────────────▼───────────────────────┐
                      │              MasterManager                   │
                      │  • Selective Sub-Feature Hierarchy Control   │
                      │  • Atomic Revert & State Synchronization     │
                      └───────┬──────────────────────────────┬───────┘
                              │                              │
              ┌───────────────▼──────────────┐ ┌─────────────▼──────────────┐
              │      AutoTweakService        │ │     MultitaskingManager    │
              │  (Event-Driven Broadcasts)   │ │ (7-Layer Kernel Whitelist) │
              └───────────────┬──────────────┘ └─────────────┬──────────────┘
                              │                              │
                      ┌───────▼──────────────────────────────▼───────┐
                      │         Root Executive Layer (libsu)         │
                      │  • Kernel Sysfs / Procfs Nodes               │
                      │  • Android Framework AppOps / DeviceIdle     │
                      │  • Iptables / TC Packet Shaping              │
                      │  • MediaTek PPM / CPUIdle C-States           │
                      └──────────────────────────────────────────────┘
```

- **Zero-Polling Event Engine (`AutoTweakService`):** Eliminates CPU drain by relying strictly on Android OS broadcast triggers (`ACTION_SCREEN_OFF`, `ACTION_SCREEN_ON`, `CONNECTIVITY_ACTION`, `BATTERY_CHANGED`) rather than infinite background sleep loops.
- **Root Abstraction Interface (`ShellUtils`):** High-speed concurrent and synchronous root command execution supporting Magisk, KernelSU, and APatch root providers.
- **Fail-Safe Persistence:** Preferences are stored in structured XML tables and validated at boot time (`BootReceiver`) to ensure settings persist smoothly across device restarts.

---

## 🎛️ Core Functional Modules

### 1. 🔋 Battery & Power Hub
Engineered to deliver aggressive battery preservation through distinct Android architectural layers:

* ⚡ **Force Doze Mode [OS Framework Layer]:**
  * Bypasses Android's default 60-minute motion-sensing idle countdown.
  * Forces the device into deep Doze state (`dumpsys deviceidle force-idle`) immediately when the screen turns off.
  * Provides an optional toggle to bypass light-doze maintenance windows.
* 📦 **App Standby Buckets Guard [Process Runtime Layer]:**
  * Demotes background battery drains into the Android `RESTRICTED` standby bucket upon screen lock.
  * Protects critical user apps in the permanent `ACTIVE` standby bucket.
  * Includes 1-Click instant action buttons: **`APPLY RESTRICTED BUCKETS NOW`** and **`RESET ALL APPS TO ACTIVE BUCKET`**.
* 🌙 **Super Doze Deep Sleep [Kernel / CPU Hardware Layer]:**
  * Activates deep CPU C-States (Core Parking & 400MHz idle frequencies) on MediaTek Dimensity architecture.
  * Suppresses background Google Cloud master synchronization (`master_sync_enabled 0`).
  * Blocks non-critical kernel wakelocks (`wlan_wake`, `wlan_rx_wake`) to achieve near **0% overnight battery drop**.
* 🔌 **Charging Protection & Hardware Bypass Lab:**
  * **Configurable Battery Charge Limit:** Automatically stops charging at predefined thresholds (e.g., 80%) to prolong battery lifespan.
  * **Direct Hardware Bypass Charging:** Powers the device directly from the charger without cycling the battery cell, preventing heat buildup during intensive gaming sessions.
  * **USB Fast Charge Force:** Overrides USB power limits on low-output charging ports.
* 🛡️ **Hardware Sensor Firewall:**
  * Permanent individual blocking switches for **5 hardware sensors**: NFC, Gyroscope, Magnetometer/Compass, Motion Accelerometer, and Ambient Light.
  * Auto-disables sensor power planes on screen off to prevent telemetry drain without breaking accessibility hooks.

---

### 2. 🚀 Performance & Display Hub
Unlocks complete manual and automated control over hardware rendering, memory, and thermal governors:

* 🖥️ **Display Resolution & Density Scaling:**
  * 1-Click resolution switcher (FHD+ 1080p, HD+ 720p, or Native default).
  * Independent custom density (DPI) adjustments for optimized UI real estate and higher rendering framerates.
* 🔄 **Dedicated Display Refresh Rate Controller:**
  * Direct hardware panel frequency locking: **60Hz**, **90Hz**, **120Hz**, or **Dynamic (LTPO/Adaptive)**.
  * Full real-time synchronization with Operating Mode presets.
* ⚡ **Dynamic ZRAM & Swappiness Engine:**
  * High-speed compressed physical RAM allocation (up to 8GB) using `zstd` / `lz4` compression algorithms.
  * Dynamic kernel swappiness and dirty page writeback tuning for lag-free application switching.
* 💾 **UFS Storage Boost:**
  * Automated filesystem trimming (`fstrim -v /data /system /cache`) on boot and scheduled intervals.
  * I/O queue scheduler optimization (`mq-deadline`, `kyber`, `none`) with elevated readahead buffers (2048KB).
* 🌡️ **Adaptive Thermal Management Engine:**
  * Real-time thermal sensor monitoring across CPU clusters and battery cells.
  * Optional thermal throttle overrides for sustained gaming performance.
* 🧹 **Deep System Optimization:**
  * Suppresses background system logging (`logd`) overhead, flushes runtime ART cache, and adjusts kernel entropy pools.

---

### 3. 🎮 Gaming & App Turbo Hub
Guarantees uninterrupted competitive gaming performance:

* 🎯 **Game Turbo Suite:**
  * Auto-detects supported game packages (BGMI, PUBG, Genshin Impact, COD Mobile, etc.) upon launch.
  * Sets top-priority CPU scheduling (`SCHED_FIFO` / `nice -20`) and elevates GPU governor floor clocks.
* 🌐 **Network Packet Guard:**
  * Implements `iptables` and Linux traffic control (`tc`) queue rules to prioritize gaming UDP/TCP packets over background sync tasks, eliminating ping spikes.
* 📱 **Per-App Display & Power Profiles:**
  * Assigns custom refresh rates, touch sampling rates, and performance governors individually per application.

---

### 4. 🛡️ Security & Network Hub
Provides granular control over network modems and system telemetry:

* 📡 **Smart Data Switcher:**
  * Fully event-driven logic that disables mobile data connectivity when connected to a stable Wi-Fi network and instantly restores it upon Wi-Fi disconnect.
* 🚀 **Google TCP BBR Congestion Control:**
  * Forces the Linux kernel network stack to use Google's BBR (Bottleneck Bandwidth and RTT) congestion algorithm for enhanced bandwidth and lower latency.
* 📶 **5G Modem Keepalive & Anti-Sleep:**
  * Prevents the cellular baseband modem from throttling to low-power 4G/3G states during standby, ideal for uninterrupted Wi-Fi hotspot sharing.
* 🗼 **Home Cell Tower Lock:**
  * Locks connection to specific local PCI/EARFCN cell towers to prevent unwanted signal roaming and indoor network drops.
* 🔍 **App Inspector & Firewall:**
  * Individual per-app internet blocking (Wi-Fi and Cellular) via `iptables` firewall rules.

---

### 5. 📦 App & Storage Management Hub
Maintains clean, uncluttered application lifecycles:

* ❄️ **App Freezer (Auto-Hibernation Engine):**
  * Freeze unused heavy apps via Linux `cgroups v2` process suspension (`SIGSTOP` / `freeze`).
  * Features **"Auto-Freeze on Screen Off"** to immediately suspend background applications when locking the device.
* 🗑️ **Root Bloatware Debloater:**
  * Safely disables or uninstalls carrier bloatware and unnecessary pre-installed OEM system packages.
* 🔐 **App & Data Backup Vault:**
  * Backup and restore application APKs and internal private data (`/data/data/`) locally.

---

## 🛡️ Universal Protected Whitelist System

To eliminate app kill issues with critical utilities (such as **Key Mapper**, accessibility gesture tools, alarms, and messaging apps), Phone Control features a **Universal Protected Whitelist Engine** managed via `MultitaskingManager.kt`.

Adding an application once to the Universal Whitelist automatically grants an unbroken **7-Layer Kernel & OS Exemption**:

```kotlin
// 1. Android Standard DeviceIdle Doze Whitelist
dumpsys deviceidle whitelist +$packageName

// 2. Android Deep Idle Sleep Exemption
dumpsys deviceidle except-idle-whitelist +$packageName

// 3. Android AppOps Background Execution Permit
cmd appops set $packageName RUN_IN_BACKGROUND allow

// 4. Android AppOps Unrestricted Background Run Permit
cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow

// 5. Android AppOps Input Hook & Wakelock Permit
cmd appops set $packageName WAKE_LOCK allow

// 6. Permanent Active Standby Bucket (Never Demoted to Restricted)
am set-standby-bucket $packageName active

// 7. Prevent Inactivity Marking
cmd activity set-inactive $packageName false
```

---

## 🤖 System Operating Modes & AI Engine

The Main Dashboard provides 1-tap switching across 4 global system profiles:

| Mode | CPU / GPU Governor | Display Refresh Rate | Background Policy | Target Usage |
| :--- | :--- | :--- | :--- | :--- |
| 🔋 **Power Saver** | Downclocked Energy-Aware | Fixed 60Hz | Aggressive Standby & Doze | Maximum battery endurance |
| ⚖️ **Balanced** | Dynamic Interactive / Schedutil | Dynamic LTPO (Auto) | Normal Android Lifecycle | Smooth daily multitasking |
| 🚀 **Performance** | Performance Governor / High Floor | Fixed 120Hz | Unrestricted Background & I/O | Competitive gaming & heavy loads |
| 🤖 **Automatic (AI)** | Dynamic Real-Time Load Sensing | Adaptive Frequency | Automated Context Tuning | Fully automated intelligent tuning |

---

## 🔄 Master Management & Safe Revert Architecture

Phone Control incorporates a **Hierarchical Master Tweak Architecture**:
- **Master Category Toggles:** Enabling a category activates its background engine while keeping sub-features selectively configurable. Turning OFF a master category immediately reverts all modified kernel parameters in that group.
- **Emergency Revert Engine (`Revert All Modifications`):**
  - Restores all CPU/GPU governors to stock `schedutil`.
  - Clears all `iptables` packet filters and network locks.
  - Restores display resolution, DPI, and refresh rate to manufacturer defaults.
  - Re-enables all hardware sensors and resets all App Standby Buckets to `active`.
  - Disables all feature flags and resets UI switches to a clean default state.

---

## 💻 Technical Stack

- **Language:** Kotlin 100%
- **Target SDK:** Android 14 / 15 (API 34/35)
- **Minimum SDK:** Android 12 (API 31)
- **Root Provider:** `libsu` / Native Android Shell Execution
- **UI Components:** Android Material 3 Design Components, ViewBinding, NestedScrollView
- **Build System:** Gradle Kotlin DSL / Groovy

---

## 📋 Installation & Requirements

### Prerequisites
1. **Primary Target Device:** **Nothing Phone (2a)** (Model: `AIN142` / `A142`)
2. **Chipset Architecture:** **MediaTek Dimensity 7200 Pro (MT6886)** (Required for 100% full hardware & kernel feature compatibility).
3. **Android Version:** **Android 12, 13, 14, 15, or 16** (Nothing OS 2.x / 3.x).
4. **Root Access:** Superuser permissions granted via **Magisk (v24.0+)**, **KernelSU (v0.6.0+)**, or **APatch**.
5. **Storage Permissions:** Required for backup/restore operations in App & Data Vault.

### Installation
1. Download the latest `app-debug.apk` from the [Releases](https://github.com/m4mental/phone-control/releases) section.
2. Install the APK on your device.
3. Launch the app and grant Superuser (Root) access when prompted.

---

## 🔨 Build Instructions

Clone the repository and build using Gradle:

```bash
# Clone the repository
git clone https://github.com/m4mental/phone-control.git
cd phone-control

# Build Debug APK
./gradlew assembleDebug

# Install to connected ADB device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## ⚠️ Disclaimer & Safety

> [!WARNING]
> Phone Control executes direct kernel and hardware-level modifications requiring Superuser privileges. While all tweaks are carefully designed with safety boundaries and verified on live hardware, the developers assume no responsibility for data loss, hardware damage, or warranty voidance. Always ensure you have a valid device backup and utilize the **"Revert All Modifications"** function before uninstallation.

---

## 📄 License
This project is open-source software licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for more details.
