# Roadmap: Kernel-Level System Engine Upgrade

This plan outlines the migration of app-level features to high-performance kernel-level optimizations. This will transform the app from a simple settings manager into a deep system engine that improves performance, reduces latency, and maximizes battery life without CPU overhead.

## User Review Required

> [!IMPORTANT]
> These changes involve modifying low-level kernel parameters (`sysctl`, `/sys/fs`, `/proc/sys`). While safe on most devices, they require stable root access.
>
> **Key Design Decisions:**
> 1. **Modular Tweak Manager**: All kernel logic will be centralized in `TweakManager.kt` and triggered by the Native Daemon.
> 2. **Profile-Based Tuning**: Instead of individual toggles, we will offer "Performance", "Battery", and "Extreme Multitasking" profiles that tune 10+ kernel values at once.
> 3. **Non-Persistent by Default**: Tweaks will be re-applied on boot via `BootReceiver` but won't permanently modify system partitions, making them 100% reversible.

## Proposed Changes

---

### 1. CPU & EAS (Energy Aware Scheduling) Tuning
**Goal**: Improve the balance between battery and performance by tuning how the kernel wakes up Big/Little cores.
- **TweakManager.kt**: Add `applyCpuTuning(mode)` to adjust `sched_latency_ns`, `sched_upmigrate`, and `sched_downmigrate`.
- **AutoTweakService.kt**: Link these to the existing Mode Control.

### 2. I/O Storage & Latency Optimizer
**Goal**: Make app launches instant and reduce UI stutters.
- **TweakManager.kt**: Implement `read_ahead_kb` optimization (e.g., 2048KB for gaming, 128KB for power save) and I/O scheduler switching (`mq-deadline`, `zen`, etc.).

### 3. Advanced LMK (Low Memory Killer) fine-tuner
**Goal**: Hold more apps in RAM without reloading.
- **TweakManager.kt**: Modify `/sys/module/lowmemorykiller/parameters/minfree` values dynamically based on selected RAM profile.

### 4. Preventive Thermal & VM Guard
**Goal**: Stop the phone from getting hot in the first place and reduce standby drain.
- **TweakManager.kt**: Tune `dirty_ratio` and `dirty_background_ratio` to reduce background disk writes.
- **ThermalManager.kt**: Add preventive throttling logic that kicks in at 42°C (before the 50°C emergency).

### 5. UI Updates
- **ModeControlActivity.kt**: Add descriptions for the new kernel-level capabilities of each mode.
- **MainActivity.kt**: Add a "Kernel Status" badge to the dashboard.

---

## Verification Plan

### Automated/Stability Tests
- Check if all `/sys` paths exist before writing to prevent errors on different kernel versions.
- Monitor `logcat` for any `Permission Denied` errors during tweak application.

### Manual Verification
- **App Launch Test**: Measure time taken to open heavy apps before and after I/O tuning.
- **Standby Test**: Leave the phone for 1 hour with "Silent System" and "VM Tuning" on to measure drain.
- **Multitasking Test**: Open 10 apps and check how many remain in memory using the Aggressive LMK profile.
