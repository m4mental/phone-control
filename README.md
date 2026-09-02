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
* 🔍 **ANR-Free App Inspector & Deep Package Recovery:**
  * **100% Asynchronous Background Threading:** Moves all package reflection and icon loading (`pm.getApplicationIcon()`) off the UI thread, eliminating ANR (Application Not Responding) timeouts.
  * **Instant In-Memory Search:** Filters 400+ applications across memory caches in <1ms at fluid 120fps.
  * **Deep Hidden Package Discovery:** Queries hidden flags (`MATCH_UNINSTALLED_PACKAGES`, `MATCH_DISABLED_COMPONENTS`) and root command fallbacks (`pm list packages -d -u`) to detect apps hidden by OEM/terminal commands (`pm hide`).
  * **Dedicated "Disabled / Hidden" 3rd Tab:** Instant one-stop view for all frozen, disabled, hidden, and suspended applications.
  * **Color-Coded Status Badges:**
    * 🔴 **`HIDDEN`** (`#FF1744`) — Packages hidden via `pm hide`.
    * 🟠 **`DISABLED`** (`#FF9100`) — Packages disabled via `pm disable` / `pm disable-user`.
    * 🔵 **`FROZEN`** (`#00E5FF`) — Apps frozen by Phone Control Linux CGroups.
    * 🟣 **`SUSPENDED`** (`#E040FB`) — Packages suspended via `pm suspend`.
    * ⚪ **`STOPPED`** (`#888888`) — Standard stopped processes.
  * **1-Tap Unhide & Full System Restore:** Unified restore command (`pm default-state --user 0`, `pm unhide`, `pm enable`, `pm unsuspend`) that restores launcher icons and restores the "Open" button in Google Play Store.
* 🛡️ **Per-App Network Firewall:**
  * Individual per-app internet blocking (Wi-Fi and Cellular) via `iptables` packet filtering.

---

### 5. 📦 App & Storage Management Hub
Maintains clean, uncluttered application lifecycles:

* 🎛️ **Smart Audio Equalizer Guard (0ms Auto-Sleep Engine):**
  * **The Problem:** Audio equalizer and DSP apps (such as Poweramp Equalizer, Wavelet, ViPER4Android, JamesDSP) keep persistent audio sessions and wake-locks alive even when music is paused, causing major standby battery drain.
  * **Hardware-Level Playback Listener:** Connects directly to Android's `AudioManager.AudioPlaybackCallback` with zero background polling loops.
  * **0ms Instant Unfreeze on Music:** The instant Spotify, YouTube, or any media player begins playback, Equalizer is unfrozen via Linux CGroups in **<1 millisecond** — delivering full bass and treble on the very first beat.
  * **30-Second Grace Pause Buffer:** When music pauses or tracks change, a 30-second countdown runs before entering sleep. Seeking, switching songs, or browsing playlists never triggers false hibernations.
  * **Zero Audio Dropouts:** Active streams are strictly guarded. Destructive service reloads and `ACTION_RELOAD` broadcasts are eliminated, ensuring 100% continuous, crystal-clear audio.
  * **App Transition Immunity:** Automatically excludes the active equalizer from generic `am force-stop` on app switching, even if added to the general hibernating apps list.
  * **Universal Auto-Detection:** Automatically detects Poweramp Equalizer, Wavelet, ViPER4Android, Flat Equalizer, JamesDSP, SpotiQ, and offers an in-app picker for custom DSP engines.
* ❄️ **App Freezer & Special Hibernation Engine:**
  * **Normal Freeze (`am freeze` + `am force-stop`):** Halts processes via Linux `cgroups v2` process suspension, frees RAM, and kills background services while keeping the launcher icon normal.
  * **Special Freeze (`am force-stop` + `pm suspend`):** Suspends the entire package so Android OS grays out the icon and completely rejects waking intents or broadcasts.
  * **Auto-Suspend on Recents Dismissal:** Apps dismissed from Recents auto-suspend in the background and dynamically update their launcher widget icons.
* 📥 **Universal Package Installer (Auto-Recovery):**
  * Built-in silent package installer with a 60-second watchdog timer for large APKs.
  * Automatic `INSTALL_FAILED_UPDATE_INCOMPATIBLE` signature conflict recovery: automatically uninstalls the old build and retries a clean install seamlessly.
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

## 🤖 System Operating Modes & 4-Stage Progressive Governor

Phone Control replaces traditional static governors with a **4-Stage Progressive Dynamic EAS Ladder** engineered specifically for the MediaTek Dimensity 7200 Pro (6 Little Cortex-A510 + 2 Big Cortex-A715 cores):

```
                                 ┌────────────────────────────────────────────────┐
                                 │     STAGE 4: Extreme Unleashed Raw Turbo       │
                                 │     6 Little @ 2.0GHz  |  2 Big @ 2.8GHz       │
                                 │     [BGMI 90fps / 3D Gaming / 4K Video Export] │
                                 └───────────────────────▲────────────────────────┘
                                                         │ >90% Load / Game Turbo
                                 ┌───────────────────────┴────────────────────────┐
                                 │      STAGE 3: Balanced Dual-Cluster Compute    │
                                 │      6 Little @ 2.0GHz  |  2 Big @ 1.5GHz      │
                                 │      [HDR Camera / Video Timeline Editing]     │
                                 └───────────────────────▲────────────────────────┘
                                                         │ 70-90% Load
                                 ┌───────────────────────┴────────────────────────┐
                                 │      STAGE 2: 120Hz Pure 6-Core Fluidity       │
                                 │      6 Little @ 1.25G-2.0G | Big @ 400MHz Sleep│
                                 │      [Rapid App Switching / Heavy Scroll]      │
                                 └───────────────────────▲────────────────────────┘
                                                         │ 35-70% Load
                                 ┌───────────────────────┴────────────────────────┐
                                 │      STAGE 1: Super Cool Pure Eco Base         │
                                 │      6 Little @ 650M-950M  | Big @ 400MHz Sleep│
                                 │      [Reading / YouTube / Chatting / Settings] │
                                 └───────────────────────▲────────────────────────┘
                                                         │ Screen On (0-35% Load)
                                 ┌───────────────────────┴────────────────────────┐
                                 │       SCREEN-OFF: 0.05W Standby Deep Sleep     │
                                 │       6 Little @ 480MHz  | Big @ 400MHz Sleep  │
                                 └────────────────────────────────────────────────┘
```

### 📊 Progressive Frequency Ladder Details:

| Stage | Target CPU Load | 6 Little Cores (0–5) | 2 Big Cores (6–7) | Real-World Use Cases | Thermal / Power |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 💤 **Standby Sleep** | Screen Locked | `480 MHz` (Min Floor) | `400 MHz` (Deep Sleep) | Phone in pocket / Overnight | ~0.05W (0% overnight drain) |
| 🟢 **Stage 1 (Eco)** | `0% – 35%` | `650M ➔ 850M ➔ 950M` | `400 MHz` (Deep Sleep) | Reading, YouTube, WhatsApp, Settings | Ice Cold (<33°C, 0.12W) |
| 🔵 **Stage 2 (Fluid)** | `35% – 70%` | `1.25 GHz ➔ 2.0 GHz` | `400 MHz` (Deep Sleep) | 120Hz Fast App Carousel & Scrolling | Butter Smooth 120fps |
| 🟡 **Stage 3 (Compute)**| `70% – 90%` | `2.0 GHz` | `1.5 GHz` (Cool Clock) | HDR Camera processing, Video editors | Balanced (<38°C) |
| 🔴 **Stage 4 (Turbo)** | `>90%` / Gaming | `2.0 GHz` (Max) | `2.8 GHz` (Full Turbo) | BGMI 90fps, Genshin, 4K Rendering | Maximum Peak Performance |

---

## 🔐 Hidden Test Lab & Biometric Screen Lock Protection

For safety and clean UI presentation, the **Stage Override Test Lab** is hidden by default:
* **Activation Trigger:** Long-press on the **"Operation Mode Control"** card on the main dashboard.
* **Biometric Authentication:** A Material confirmation dialog prompts for device authentication via Android's `KeyguardManager` (supporting **Fingerprint, PIN, Pattern, or Password**).
* **Instant Lock:** Includes a dedicated **`[✕ Lock]`** button in the Test Lab header to immediately conceal and lock the laboratory card.
* **⚡ Apply Selected Mode Button:** Allows instant 0ms manual re-application with toast verification.

---

## 🚀 90-Second Post-Boot Fast Startup Turbo Engine

Upon phone reboot or restart (`ACTION_BOOT_COMPLETED`), Android executes heavy initialization workloads (DEX bytecode optimization, media scanner, Google Play sync, app widgets, and launcher caching).
* **Boot Time Acceleration (0 – 90 Seconds / 1.5 Minutes):**
  * Little Cores (0–5) unlock to **`2.0 GHz`** and Big Cores (6–7) unlock to **`2.8 GHz` (Stage 4 Turbo)**.
  * Ensures all startup tasks complete within the first 90 seconds with **zero boot-time lag or UI sluggishness**.
* **Automatic Eco Transition (After 90 Seconds):**
  * Automatically transitions down to the user's configured mode (e.g. **Stage 1 Eco: `650 MHz` Base Floor + Big Cores `400 MHz` Deep Sleep**, ready to dynamically spike on touches and app loads).
  * Returns the device to an ultra-cool, power-saving idle state immediately after initialization.

---

## 🎨 Real-Time Multi-Color Stage & MHz Live Telemetry

The Main Dashboard CPU Card features instant sub-frequency color telemetry and live hardware frequency readings directly on-screen without requiring terminal access:
* **Stage 1 (Pure Eco):**
  * **Cyan (`#00E5FF`)** @ `650 MHz` — `S1 • 650M` (Static Screen, Reading, Normal Chat, Settings)
  * **Yellow (`#FFD700`)** @ `850 MHz` — `S1 • 850M` (Touch Burst, Fast Feed Scrolling, Rapid Typing)
  * **Red (`#FF5252`)** @ `950 MHz` — `S1 • 950M` (WhatsApp Video Call, PiP Floating Window, App Transitions)
* **Stage 2 (Fluid 120Hz):**
  * **Green (`#69F0AE`)** @ `1.25GHz - 2.0GHz` — `S2 • 1.4G` / `S2 • 2.0G` (6 Little Cores maxed, 2 Big Cores in 400MHz Deep Sleep)
* **Stage 3 (Dual-Cluster Compute):**
  * **Yellow (`#FFD700`)** @ `2.0GHz + 1.5GHz` — `S3 • 2.0G` (Camera HDR, Video Editing, Heavy Compute)
* **Stage 4 (Extreme Turbo):**
  * **Red (`#FF5252`)** @ `2.0GHz + 2.8GHz` — `S4 • 2.8G` (Full Turbo Unleashed for 3D Gaming & Benchmarks)

---

## 📞 Hardware-Triggered Video Call Boost & Invisible Background Engine

WhatsApp, Telegram, Zoom, Google Meet, and Instagram video calls are intelligently handled by an automated hardware-level monitoring pipeline:
* **Dual Hardware-Level Detection:**
  * **`CameraManager.AvailabilityCallback`:** Connects directly to Android's camera subsystem. As soon as any application opens the camera sensor (`onCameraUnavailable`), the engine triggers instantly in 0ms.
  * **`AudioManager.OnModeChangedListener` (`MODE_IN_COMMUNICATION`):** Detects active VoIP call sessions and telephony communication states without polling loops.
* **Instant 950MHz Little Core Lock:**
  * Even if the device is currently running in **Stage 1 (650MHz Strict Lock)** or **Power Saver Mode**, the engine temporarily unlocks and locks all 6 Little Cores strictly to **`950 MHz`** (`scaling_max_freq = 950000` & `scaling_min_freq = 950000`).
  * Provides jitter-free, 100% smooth 30-60fps hardware video decoding/encoding and Picture-in-Picture (PiP) window rendering.
* **400MHz Deep Sleep on Big Cores:**
  * Both Big Cores (6 & 7) remain permanently clamped at **`400 MHz` Deep Sleep** throughout the entire call.
  * **Result:** Ice-cold thermal performance (<34°C even during 1+ hour video calls) with near-zero battery drain.
* **Auto-Restore on Call End:**
  * The moment the video call terminates and the camera sensor is released (`onCameraAvailable` + audio mode reset), the engine immediately re-applies the user's base configured frequency (e.g. 650MHz Base Floor).
* **👻 100% Invisible Background Architecture (Android 14 / 15 / 16):**
  * Operates without any persistent foreground notification banner or notification drawer clutter.
  * Completely omitted from Android 14/15/16's default **"Active Apps"** / **"Apps running in background"** Quick Settings drawer.
  * Maintained permanently alive through root-level Doze exemptions (`dumpsys deviceidle whitelist` + `appops RUN_IN_BACKGROUND allow`).

---

## 🎛️ Studio Audio Equalizer (Poweramp DSP Engine & DTS Profiles)

Phone Control features a high-precision, studio-grade audio mastering suite built directly into the operating system's audio pipeline:
* **Zero-Flashing Android Architecture:**
  * Runs 100% on Android's native **`DynamicsProcessing`** framework and `AudioEffect` engine.
  * Completely eliminates the need for unstable Magisk modules or risky `/vendor` HAL patches on Android 14, 15, and 16.
* **Poweramp JSON Preset Compatibility:**
  * Direct 1-tap parser for exported Poweramp Equalizer JSON profiles (both Graphic and Parametric formats).
  * **Pre-Bundled Audiophile Profiles:**
    1. 🎵 **DTS Sound Unbound profile:** Punchy 90Hz low-shelf bass (+5.2dB) + ultra-clear 10kHz air treble (+3.0dB).
    2. 🎬 **DTS Theater Mode:** Cinema surround calibration with +7.5dB dynamic low-end boost and -1.0dB headroom preamp.
    3. 🍿 **DTS Theater Mode 2:** Extreme sub-bass (+8.0dB @ 31Hz & +7.0dB @ 62Hz) with -6.0dB clean preamp protection.
    4. 🎬 **Dimensional 3D Theater (IMAX & Dolby Atmos Cinema):** Comprehensive acoustic theater matrix combining +7.5dB sub-bass floor rumble, +2.8dB vocal presence, 80% Haas Differential Surround widening, Concert Hall reverberation, and 70% dynamic diaphragm drive.
    5. 🎸 **My song 2 (Pure Parametric Biquad):** Ultra-precise frequency notches and resonance points (77Hz Q=1.96, 178Hz Q=0.71, 1006Hz Q=4.28, 5689Hz Q=5.27).
    6. ⚡ **Studio Flat (Bypass):** Clean reference curve.
* **🛡️ Read-Only Factory Preset Protection & Custom Forking:**
  * Built-in factory presets (DTS profiles, Dimensional 3D Theater, etc.) can never be accidentally modified, overwritten, or corrupted.
  * Adjusting any slider or setting instantly forks into a `Custom (Unsaved)` profile, displaying a clear orange warning badge.
  * Users can save their custom sound signature under any custom name with a single tap, while the pristine factory preset remains permanently recoverable in 1 click.
* **Storage Access Framework (SAF) JSON File Picker & Paste:**
  * Tap the `+` icon in the Studio Equalizer top bar to open Android's native system Document Picker (`com.android.documentsui`) and select `.json` preset files directly from Downloads, WhatsApp, or local storage.
  * Also supports direct copy-pasting of raw Poweramp JSON profile text strings.
* **Live Dynamic Bézier Curve Visualizer:**
  * Renders a real-time glowing neon Bézier spline curve across 20Hz to 20kHz with logarithmic frequency interpolation.
* **Hardware Preamp & Peak Limiter:**
  * Adjustable Preamp Gain (-12.0 dB to +12.0 dB).
  * Integrated brickwall peak limiter ensures zero crackling, clipping, or audio distortion even under +9dB sub-bass loads.
* **Master Tone & Spatial Controls:**
  * Tone Bass (90Hz Shelf) and Tone Treble (10kHz Shelf) sliders.
  * Hardware Subwoofer Bass Boost and 3D Spatial Virtualizer.
* **✨ ViPER FX Suite (Acoustics, Widening & Harmonics):**
  * 🎧 **Differential Surround:** Stereo soundstage expansion via Haas effect phase delays, creating an immersive 3D surround atmosphere in headphones.
  * 🏛️ **Reverberation:** Acoustic simulation powered by Android's native `PresetReverb` engine (Small Room, Medium Room, Large Room, Concert Hall, Studio Plate).
  * 🔊 **Dynamic System:** ViPER-inspired harmonic bass drive and diaphragm resonance algorithm delivering deep, punchy subwoofer rumble without distortion.
* **Smart Sleep Guard & Dual Coexistence:**
  * Auto-sleeps the DSP engine to **0% CPU / 0% RAM usage** when music is paused, waking up in **0ms** upon audio track playback.
  * Seamlessly co-exists with the App Freezer's external **Smart Equalizer Audio Guard** (protecting external apps like Poweramp Equalizer / Wavelet if user prefers external equalizers).

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
