# Android 12 System NTP Setup Wizard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a three-step installation wizard that can test and apply the Android 12 system NTP server using verified Device Owner and `WRITE_SECURE_SETTINGS` capabilities without creating an app-owned clock.

**Architecture:** Keep host validation and SNTP packet rules in pure Java testable units. Put all Android system writes in one `SystemNtpConfigurator`; keep the Activity responsible only for page navigation and asynchronous UI orchestration. The system `ntp_server` remains the source of truth and no force-refresh shell command is used.

**Tech Stack:** Java 8, AndroidX AppCompat, Android DevicePolicyManager, Settings.Global, Java DatagramSocket, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-02-system-ntp-setup-wizard-design.md`

## Global Constraints

- Target deployment is Android 12; app still compiles with minSdk 24 / targetSdk 34.
- The app is Device Owner, but `WRITE_SECURE_SETTINGS` must still be checked at runtime because uninstall/reinstall loses the ADB grant.
- Do not change `ntp_timeout`, network-time polling intervals, system clock directly, Face SDK scheduling, or punch timestamp semantics.
- NTP test traffic is UDP/123 only and never changes system/app time.
- The repository workspace is an extracted delivery tree without `.git`; record verification results instead of creating commits.

---

### Task 1: Pure NTP host policy

**Files:**
- Create: `app/src/main/java/com/punch/app/utils/SystemNtpPolicy.java`
- Create: `app/src/test/java/com/punch/app/utils/SystemNtpPolicyTest.java`

**Interfaces:**
- Produces: `SystemNtpPolicy.normalizeHost(String)`, `SystemNtpPolicy.isValidHost(String)`, `SystemNtpPolicy.ManagementAvailability`, `SystemNtpPolicy.getAvailability(boolean, boolean, int)`.

- [ ] **Step 1: Write failing tests** for valid IPv4/DNS hosts, invalid URI/port/whitespace inputs, normalization, and Device Owner / permission / API capability classification.
- [ ] **Step 2: Run the isolated JUnit class** with the repository JUnit jars and verify RED because `SystemNtpPolicy` does not exist.
- [ ] **Step 3: Implement the minimal pure Java policy** without Android imports.
- [ ] **Step 4: Re-run the isolated JUnit class** and require all tests PASS.

### Task 2: SNTP packet codec

**Files:**
- Create: `app/src/main/java/com/punch/app/utils/NtpPacket.java`
- Create: `app/src/test/java/com/punch/app/utils/NtpPacketTest.java`

**Interfaces:**
- Produces: `NtpPacket.newClientRequest(long nowMillis)`, `NtpPacket.parseResponse(byte[], int, long, long)`, and immutable `NtpPacket.Result`.

- [ ] **Step 1: Write failing tests** for a 48-byte client request, NTP epoch conversion, valid server response parsing, bad mode, invalid stratum, short packet, and zero transmit timestamp.
- [ ] **Step 2: Run isolated tests** and verify RED because `NtpPacket` does not exist.
- [ ] **Step 3: Implement minimal encode/decode/validation logic** with no network or Android dependency.
- [ ] **Step 4: Re-run isolated tests** and require all tests PASS.

### Task 3: Blocking NTP probe client

**Files:**
- Create: `app/src/main/java/com/punch/app/utils/NtpProbeClient.java`

**Interfaces:**
- Consumes: `SystemNtpPolicy.normalizeHost/isValidHost`, `NtpPacket`.
- Produces: `NtpProbeClient.probe(String host, int timeoutMs)` returning `NtpProbeClient.ProbeResult`.

- [ ] **Step 1: Add policy-level tests** that timeout is constrained to the internal supported range using a pure helper in `NtpProbeClient` or `SystemNtpPolicy`.
- [ ] **Step 2: Verify the new timeout-policy test fails** before implementation.
- [ ] **Step 3: Implement UDP/123 probe** using `DatagramSocket`, one request, one response, bounded timeout, and structured failure messages; do not add retry loops.
- [ ] **Step 4: Re-run pure Java policy/packet tests** to ensure no regression; no external NTP endpoint is required for automated tests.

### Task 4: Android system NTP configurator

**Files:**
- Create: `app/src/main/java/com/punch/app/utils/SystemNtpConfigurator.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SystemNtpPolicy`, `KioskManager`, `KioskDeviceAdminReceiver`.
- Produces: `getAvailability(Context)`, `getCurrentServer(Context)`, `apply(Context, String)`, immutable `ApplyResult`.

- [ ] **Step 1: Add static source tests** in `scripts/tests/test_system_ntp_static.py` asserting the manifest permission is present and all `Settings.Global` NTP writes live in `SystemNtpConfigurator`, not in the Activity.
- [ ] **Step 2: Run the static test and verify RED** because the permission/configurator are not present.
- [ ] **Step 3: Add `<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"/>`** to the manifest.
- [ ] **Step 4: Implement capability detection, read-back-verified `ntp_server` write, Device Owner auto-time enable, date/time restriction, and best-effort rollback.** Use literal system key `ntp_server` to avoid relying on hidden/non-SDK constants.
- [ ] **Step 5: Re-run the static test** and require PASS.

### Task 5: Three-page installation wizard UI

**Files:**
- Modify: `app/src/main/java/com/punch/app/activity/SetupWizardActivity.java`
- Modify: `app/src/main/res/layout/activity_setup_wizard.xml`
- Modify: `scripts/tests/test_system_ntp_static.py`

**Interfaces:**
- Consumes: `SystemNtpConfigurator`, `NtpProbeClient`.
- Produces: Wi-Fi → System NTP → Server page navigation; NTP probe/apply interactions.

- [ ] **Step 1: Extend the static test** to require page IDs, NTP controls, `PAGE_NTP=1`, `PAGE_SERVER=2`, executor shutdown, and no direct `Settings.Global.putString` in `SetupWizardActivity`.
- [ ] **Step 2: Run the static test and verify RED** against the existing two-page wizard.
- [ ] **Step 3: Add the NTP page XML** matching the existing visual language: capability/current server text, host field, test/apply buttons, status text, previous/next buttons.
- [ ] **Step 4: Update `SetupWizardActivity`** to initialize current state, handle three-page back/next navigation, run probe/apply on one background executor, ignore callbacks after destruction, and require successful apply before continuing to server setup.
- [ ] **Step 5: Re-run static tests** and require PASS.

### Task 6: Deployment and operator documentation

**Files:**
- Create: `docs/SYSTEM-NTP-SETUP-ANDROID12.md`
- Modify: `README.md`

**Interfaces:**
- Documents the required ADB grant and verification commands without changing runtime behavior.

- [ ] **Step 1: Document provisioning** including `pm grant`, permission verification, `settings get global ntp_server`, and `dumpsys network_time_update_service`.
- [ ] **Step 2: Document semantic limits**: write success is not proof of immediate NTP refresh; uninstall/reinstall loses the grant; no app-owned offset is used.
- [ ] **Step 3: Add a concise README pointer** to the new document.

### Task 7: Verification and delivery

**Files:**
- Verify all files changed above.

**Interfaces:**
- Produces a tested source patch; does not install or modify a real device.

- [ ] **Step 1: Run all new pure Java JUnit tests** and require zero failures.
- [ ] **Step 2: Run `python3 -m unittest scripts.tests.test_system_ntp_static`** and require PASS.
- [ ] **Step 3: Run existing relevant static regression tests** including `test_face_scheduler_static.py` to prove Face SDK paths were untouched.
- [ ] **Step 4: Attempt Gradle verification** using the repository wrapper; if `gradle-wrapper.jar` is still missing, record the exact blocker and do not claim Android build success.
- [ ] **Step 5: Run XML parsing / Java syntax-oriented checks available in the environment** and verify generated patch contents.
- [ ] **Step 6: Produce a V5-NTP-only incremental ZIP** containing only this feature's source/docs and a SHA256 manifest; do not include APK/AAB artifacts.
