from __future__ import annotations

import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def read_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class SmokeVariantContractTest(unittest.TestCase):
    def test_gradle_targets_smoke_for_instrumentation(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertRegex(gradle, r'(?m)^\s*testBuildType\s+[\'\"]smoke[\'\"]\s*$')

    def test_smoke_has_kiosk_home_test_host(self) -> None:
        path = ROOT / "app/src/smoke/java/com/punch/app/activity/UiTestKioskHomeHostActivity.java"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        self.assertIn("extends KioskHomeActivity", text)
        self.assertIn("void allowFinish()", text)

    def test_debug_has_setup_wizard_test_host(self) -> None:
        path = ROOT / "app/src/debug/java/com/punch/app/activity/UiTestSetupWizardHostActivity.java"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        self.assertIn("extends SetupWizardActivity", text)
        self.assertIn("KioskManager.setUiTestBypassForTest(true)", text)

    def test_smoke_manifest_registers_all_test_hosts(self) -> None:
        manifest = ROOT / "app/src/smoke/AndroidManifest.xml"
        root = ET.parse(manifest).getroot()
        application = root.find("application")
        self.assertIsNotNone(application)
        names = {
            node.attrib.get(ANDROID_NS + "name")
            for node in application.findall("activity")
        }
        expected = {
            ".activity.UiTestLoginHostActivity",
            ".activity.UiTestAdvancedConfigHostActivity",
            ".activity.UiTestSetupWizardHostActivity",
            ".activity.UiTestKioskHomeHostActivity",
        }
        self.assertTrue(expected.issubset(names), f"missing={expected - names}")


class ScriptContractTest(unittest.TestCase):
    REQUIRED_SCRIPTS = [
        "scripts/lib/DeviceTestCommon.ps1",
        "scripts/run-ui-smoke.ps1",
        "scripts/run-device-tests.ps1",
        "scripts/run-regression.ps1",
        "scripts/collect-device-metrics.ps1",
        "scripts/restore-production-from-report.ps1",
    ]

    def test_required_scripts_exist(self) -> None:
        missing = [path for path in self.REQUIRED_SCRIPTS if not (ROOT / path).is_file()]
        self.assertEqual([], missing)

    def test_v1_scripts_do_not_contain_destructive_device_operations(self) -> None:
        forbidden = [
            r"dpm\s+set-device-owner",
            r"dpm\s+remove-active-admin",
            r"\bpm\s+clear\b",
            r"\badb(?:\.exe)?\b[^\r\n]*\breboot\b",
            r"\badb(?:\.exe)?\b[^\r\n]*\buninstall\b",
            r"recovery\s+--wipe_data",
        ]
        for relative in self.REQUIRED_SCRIPTS:
            text = read_text(relative)
            for pattern in forbidden:
                self.assertIsNone(
                    re.search(pattern, text, re.IGNORECASE),
                    f"{relative} contains forbidden operation matching {pattern}",
                )

    def test_common_adb_helper_always_targets_serial(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Invoke-Adb", text)
        self.assertRegex(text, r'(?s)function\s+Invoke-Adb.*?-s.*?\$Serial')

    def test_native_command_helpers_neutralize_stderr_erroractionpreference(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Invoke-NativeCapture", text)
        self.assertIn("function Invoke-LoggedCommand", text)
        self.assertRegex(text, r'(?s)function\s+Invoke-NativeCapture.*?ErrorActionPreference\s*=\s*[\'"]Continue[\'"]')
        self.assertRegex(text, r'(?s)function\s+Invoke-LoggedCommand.*?ErrorActionPreference\s*=\s*[\'"]Continue[\'"]')
        self.assertIn('Invoke-NativeCapture -FilePath "java"', text)
        self.assertIn("Invoke-NativeCapture -FilePath $gradlew", text)

    def test_logged_command_stringifies_native_stderr_before_host_rendering(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        body = re.search(r'(?s)function\s+Invoke-LoggedCommand\s*\{(.*?)\n\}', text)
        self.assertIsNotNone(body)
        body_text = body.group(1)
        self.assertNotIn('2>&1 | Tee-Object', body_text)
        self.assertRegex(body_text, r'2>&1\s*\|\s*ForEach-Object\s*\{[^}]*\$_\.ToString\(\)')
        self.assertIn('Tee-Object -FilePath $LogPath', body_text)
        self.assertIn('Write-Host', body_text)


    def test_smoke_runner_builds_and_runs_expected_instrumentation(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn(":app:assembleSmoke", text)
        self.assertIn(":app:assembleSmokeAndroidTest", text)
        self.assertIn("com.punch.app.smoke.test/com.punch.app.test.UiSmokeTestRunner", text)
        self.assertIn("install", text)
        self.assertIn("am", text)
        self.assertIn("instrument", text)
        self.assertIn("finally", text)
        self.assertIn("Find-AppFatalEvents", text)


    def test_smoke_runner_has_device_owner_preflight_and_timeout(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("InstrumentationTimeoutSeconds", text)
        self.assertIn("Get-SmokeDeviceConflict", text)
        self.assertIn("device-policy.txt", text)
        self.assertIn("activity-state.txt", text)
        self.assertIn("Invoke-AdbWithTimeout", text)

    def test_common_library_detects_device_owner_kiosk_conflicts(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Get-SmokeDeviceConflict", text)
        self.assertIn("function Invoke-AdbWithTimeout", text)
        self.assertIn("dpm", text)
        self.assertIn("list-owners", text)
        self.assertIn("dumpsys", text)
        self.assertIn("device_policy", text)
        self.assertIn("mResumedActivity", text)
        self.assertIn("mLockTaskModeState", text)

    def test_smoke_runner_can_opt_in_to_temporarily_stop_production_kiosk(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("ProductionPackageName", text)
        self.assertIn("force-stop", text)
        self.assertIn("KioskHomeActivity", text)

    def test_smoke_timeout_defaults_to_bounded_value(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        match = re.search(r"\[int\]\$InstrumentationTimeoutSeconds\s*=\s*(\d+)", text)
        self.assertIsNotNone(match)
        seconds = int(match.group(1))
        self.assertGreaterEqual(seconds, 60)
        self.assertLessEqual(seconds, 600)

    def test_daily_runner_includes_unit_and_smoke_gates(self) -> None:
        text = read_text("scripts/run-device-tests.ps1")
        self.assertIn(":app:testDebugUnitTest", text)
        self.assertIn("run-ui-smoke.ps1", text)
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("InstrumentationTimeoutSeconds", text)

    def test_regression_runner_contains_all_local_quality_gates(self) -> None:
        text = read_text("scripts/run-regression.ps1")
        for task in [
            ":app:testDebugUnitTest",
            ":app:assembleDebug",
            ":app:assembleRelease",
            ":app:lintDebug",
        ]:
            self.assertIn(task, text)
        self.assertIn("run-ui-smoke.ps1", text)
        self.assertIn("SkipDevice", text)
        self.assertIn("StopProductionAppForSmoke", text)
        self.assertIn("InstrumentationTimeoutSeconds", text)

    def test_metrics_collector_captures_core_signals(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        for token in [
            "dumpsys meminfo",
            "dumpsys battery",
            "dumpsys thermalservice",
            "dumpsys cpuinfo",
            "df /data",
        ]:
            self.assertIn(token, text)

    def test_primary_scripts_use_timestamped_test_results(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("test-results", common)
        self.assertIn("yyyyMMdd-HHmmss", common)

    def test_manual_restore_script_uses_backup_and_restores_kiosk(self) -> None:
        text = read_text("scripts/restore-production-from-report.ps1")
        self.assertIn("production-backup", text)
        self.assertIn("Restore-InstalledPackageApks", text)
        self.assertIn("KioskHomeActivity", text)
        self.assertIn("debug.punch.device_test_maintenance", text)


class DeviceOwnerMaintenanceBridgeContractTest(unittest.TestCase):
    def test_gradle_defines_device_owner_test_build_without_suffix(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertRegex(gradle, r"(?s)deviceOwnerTest\s*\{.*?initWith\s+debug.*?signingConfig\s+signingConfigs\.debug")
        block = re.search(r"(?s)deviceOwnerTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertNotIn("applicationIdSuffix", block.group(1))

    def test_device_owner_test_manifest_exposes_only_maintenance_activity(self) -> None:
        manifest = ROOT / "app/src/deviceOwnerTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        root = ET.parse(manifest).getroot()
        app = root.find("application")
        self.assertIsNotNone(app)
        self.assertEqual(".DeviceOwnerTestApplication", app.attrib.get(ANDROID_NS + "name"))
        activities = {n.attrib.get(ANDROID_NS + "name"): n for n in app.findall("activity")}
        node = activities.get(".activity.DeviceOwnerTestControlActivity")
        self.assertIsNotNone(node)
        self.assertEqual("true", node.attrib.get(ANDROID_NS + "exported"))

    def test_device_owner_test_sources_exist(self) -> None:
        expected = [
            "app/src/deviceOwnerTest/java/com/punch/app/DeviceOwnerTestApplication.java",
            "app/src/deviceOwnerTest/java/com/punch/app/DeviceOwnerTestRuntimeFlags.java",
            "app/src/deviceOwnerTest/java/com/punch/app/activity/DeviceOwnerTestControlActivity.java",
            "app/src/deviceOwnerTest/java/com/punch/app/receiver/DeviceOwnerTestControlReceiver.java",
        ]
        for relative in expected:
            self.assertTrue((ROOT / relative).is_file(), relative)

    def test_kiosk_manager_has_maintenance_mode_policy_controls(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        for token in [
            "setDeviceTestMaintenanceModeForTest",
            "isDeviceTestMaintenanceModeForTest",
            "enterDeviceTestMaintenanceMode",
            "restoreDeviceOwnerKioskAfterTest",
            "setPackagesSuspended",
            "clearPackagePersistentPreferredActivities",
        ]:
            self.assertIn(token, text)

    def test_kiosk_enter_uses_activity_as_policy_context(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        match = re.search(
            r"(?s)private static void enterIfPossible\(Activity activity, int attempt\) \{(.*?)\n    \}",
            text,
        )
        self.assertIsNotNone(match)
        body = match.group(1)
        self.assertIn("ensureOwnerKioskPolicies(activity);", body)
        self.assertNotIn("ensureOwnerKioskPolicies(context);", body)

    def test_application_suppresses_watchdog_during_maintenance(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.assertIn("isDeviceTestMaintenanceModeForTest", text)
        self.assertRegex(text, r"(?s)kioskForegroundWatchdogRunnable.*?isDeviceTestMaintenanceModeForTest")

    def test_smoke_runner_supports_transactional_device_owner_bridge(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        for token in [
            "UseDeviceOwnerMaintenanceBridge",
            ":app:assembleDeviceOwnerTest",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "debug.punch.device_test_maintenance",
            "DeviceOwnerTestControlReceiver",
            "maintenance-enter",
            "production-backup",
        ]:
            self.assertIn(token, text)

    def test_common_library_can_backup_and_restore_installed_apks(self) -> None:
        text = read_text("scripts/lib/DeviceTestCommon.ps1")
        self.assertIn("function Backup-InstalledPackageApks", text)
        self.assertIn("function Restore-InstalledPackageApks", text)
        self.assertIn("pm", text)
        self.assertIn("path", text)
        self.assertIn("install-multiple", text)

    def test_runner_never_force_stops_active_production_device_owner(self) -> None:
        text = read_text("scripts/run-ui-smoke.ps1")
        self.assertNotIn('@("shell", "am", "force-stop", $ProductionPackageName)', text)

    def test_maintenance_control_is_not_declared_in_release_or_smoke_manifests(self) -> None:
        for relative in ["app/src/main/AndroidManifest.xml", "app/src/smoke/AndroidManifest.xml"]:
            text = read_text(relative)
            self.assertNotIn("DeviceOwnerTestControlReceiver", text)

    def test_v14_maintenance_bridge_uses_broadcast_receiver(self) -> None:
        manifest = read_text("app/src/deviceOwnerTest/AndroidManifest.xml")
        self.assertIn(".receiver.DeviceOwnerTestControlReceiver", manifest)
        runner = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn('"broadcast"', runner)
        self.assertIn("DeviceOwnerTestControlReceiver", runner)

    def test_v14_maintenance_revokes_all_lock_task_authorization(self) -> None:
        text = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        match = re.search(r"public static boolean enterDeviceTestMaintenanceMode\(Context context, String\[\] testPackages\)(.*?)(?=\n    public static boolean restoreDeviceOwnerKioskAfterTest)", text, re.S)
        self.assertIsNotNone(match, "Context-based maintenance method must exist")
        body = match.group(1)
        self.assertIn("dpm.setLockTaskPackages(admin, new String[]{})", body)
        self.assertNotRegex(body, r"(?<![A-Za-z0-9_.])stopLockTask\s*\(")
        self.assertNotIn("mergePackages", body)

    def test_v14_harness_polls_for_maintenance_readiness(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        runner = read_text("scripts/run-ui-smoke.ps1")
        self.assertIn("function Wait-DeviceOwnerMaintenanceReady", common)
        self.assertIn("Wait-DeviceOwnerMaintenanceReady", runner)
        self.assertIn("MaintenanceReadyTimeoutSeconds", runner)


class KioskSystemInfoStatusBarContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.kiosk = read_text("app/src/main/java/com/punch/app/utils/KioskManager.java")
        match = re.search(
            r"(?s)public static void ensureOwnerKioskPolicies\(Context context\) \{(.*?)\n    \}\n\n    public static void setDeviceTestMaintenanceModeForTest",
            self.kiosk,
        )
        self.assertIsNotNone(match, "ensureOwnerKioskPolicies must exist")
        self.body = match.group(1)

    def test_owner_kiosk_exposes_system_info_and_keeps_global_actions(self) -> None:
        self.assertIn("DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS", self.body)
        self.assertIn("DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO", self.body)

    def test_owner_kiosk_keeps_status_bar_visible(self) -> None:
        self.assertIn("dpm.setStatusBarDisabled(admin, false);", self.body)
        self.assertNotIn("dpm.setStatusBarDisabled(admin, true);", self.body)

    def test_owner_kiosk_does_not_open_navigation_or_notifications(self) -> None:
        for token in [
            "LOCK_TASK_FEATURE_NOTIFICATIONS",
            "LOCK_TASK_FEATURE_HOME",
            "LOCK_TASK_FEATURE_OVERVIEW",
        ]:
            self.assertNotIn(token, self.body)


class PerformanceHarnessV21ContractTest(unittest.TestCase):
    REQUIRED_V2_SCRIPTS = [
        "scripts/lib/PerformanceMetrics.ps1",
        "scripts/run-performance-baseline.ps1",
        "scripts/run-memory-soak.ps1",
    ]

    def test_v21_performance_scripts_exist(self) -> None:
        missing = [path for path in self.REQUIRED_V2_SCRIPTS if not (ROOT / path).is_file()]
        self.assertEqual([], missing)

    def test_performance_library_collects_required_runtime_signals(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "function Get-AppPerformanceSample",
            "dumpsys\", \"meminfo",
            "dumpsys\", \"cpuinfo",
            "/proc/$appPid/task",
            "/proc/$appPid/fd",
            "dumpsys\", \"battery",
            "df\", \"/data",
            "TotalPssMb",
            "JavaHeapMb",
            "NativeHeapMb",
            "RssMb",
            "CpuPercent",
            "ThreadCount",
            "FdCount",
            "BatteryTemperatureC",
        ]:
            self.assertIn(token, text)

    def test_performance_analysis_uses_warmup_and_stable_window_medians(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "function Get-MemorySoakAnalysis",
            "WarmupMinutes",
            "Get-Median",
            "stableSamples",
            "firstWindow",
            "lastWindow",
            "PssGrowthPercent",
            "NativeHeapGrowthPercent",
            "JavaHeapGrowthPercent",
            "PssSlopeMbPerHour",
            "CpuP95Percent",
        ]:
            self.assertIn(token, text)

    def test_memory_soak_default_is_30_minutes_at_60_seconds(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        self.assertRegex(text, r"\[int\]\$Minutes\s*=\s*30")
        self.assertRegex(text, r"\[int\]\$IntervalSeconds\s*=\s*60")
        self.assertRegex(text, r"\[int\]\$WarmupMinutes\s*=\s*5")

    def test_memory_soak_generates_csv_text_html_and_raw_evidence(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        for token in [
            "metrics.csv",
            "summary.txt",
            "report.html",
            "raw-samples",
            "logcat.txt",
            "fatal-events.txt",
            "Export-Csv",
            "Write-PerformanceHtmlReport",
            "Find-AppFatalEvents",
        ]:
            self.assertIn(token, text)

    def test_memory_soak_has_hard_gates_for_restart_fatal_and_resource_growth(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        for token in [
            "ProcessRestartCount",
            "FatalEventCount",
            "MaxPssGrowthPercent",
            "MaxNativeHeapGrowthPercent",
            "MaxJavaHeapGrowthPercent",
            "MaxThreadGrowth",
            "MaxFdGrowth",
            "Status",
        ]:
            self.assertIn(token, text)

    def test_v21_scripts_are_non_destructive(self) -> None:
        forbidden = [
            r"dpm\s+set-device-owner",
            r"dpm\s+remove-active-admin",
            r"\bpm\s+clear\b",
            r"\bforce-stop\b",
            r"\breboot\b",
            r"\buninstall\b",
            r"setLockTaskPackages",
            r"setPackagesSuspended",
        ]
        for relative in self.REQUIRED_V2_SCRIPTS:
            if not (ROOT / relative).exists():
                continue
            text = read_text(relative)
            for pattern in forbidden:
                self.assertIsNone(re.search(pattern, text, re.IGNORECASE), f"{relative}: {pattern}")

    def test_performance_library_does_not_shadow_powershell_pid_automatic_variable(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        self.assertIsNone(re.search(r"(?i)\$pid\b", text), "PowerShell $PID is a read-only automatic variable")
        self.assertIn("$appPid", text)

    def test_memory_soak_avoids_windows_powershell_generic_list_array_subexpression_bug(self) -> None:
        text = read_text("scripts/run-memory-soak.ps1")
        self.assertNotIn("@($samples)", text)
        self.assertIn("$samples.ToArray()", text)

    def test_analysis_avoids_windows_powershell_generic_list_array_subexpression_bug(self) -> None:
        text = read_text("scripts/lib/PerformanceMetrics.ps1")
        self.assertNotIn("@($failureReasons)", text)
        self.assertIn("$failureReasons.ToArray()", text)

    def test_v21_documentation_exists(self) -> None:
        self.assertTrue((ROOT / "PERFORMANCE-HARNESS-V2.1.md").is_file())



class CameraFaceHarnessV22ContractTest(unittest.TestCase):
    def test_v22_camera_face_soak_contract(self) -> None:
        runner = ROOT / "scripts" / "run-camera-face-soak.ps1"
        lib = ROOT / "scripts" / "lib" / "CameraFaceMetrics.ps1"
        doc = ROOT / "CAMERA-FACE-SOAK-V2.2.md"
        self.assertTrue(runner.exists(), "missing V2.2 camera/face soak runner")
        self.assertTrue(lib.exists(), "missing V2.2 camera/face metrics library")
        self.assertTrue(doc.exists(), "missing V2.2 camera/face soak documentation")

        runner_text = runner.read_text(encoding="utf-8-sig")
        core = ROOT / "scripts" / "run-camera-face-soak-core.ps1"
        if core.exists():
            runner_text += "\n" + core.read_text(encoding="utf-8-sig")
        lib_text = lib.read_text(encoding="utf-8-sig")
        for token in [
            "Minutes = 480",
            "WarmupMinutes = 15",
            "AutoOpenPunchScreen",
            "Get-CameraFaceHealthSample",
            "Get-CameraFaceSoakAnalysis",
            "camera-face-soak",
        ]:
            self.assertIn(token, runner_text)
        for token in [
            "uiautomator",
            "nav_punch",
            "btn_punch_toggle",
            "打卡：关",
            "Enable-PunchIfNeeded",
            'dumpsys\", \"media.camera',
            "libbdface_sdk",
            "libonnxruntime",
            "CameraActive",
            "FaceSdkLoaded",
            "MainActivityForeground",
            "Write-CameraFaceSummary",
            "Write-CameraFaceHtmlReport",
        ]:
            self.assertIn(token, lib_text)

    def test_v223_activity_parser_supports_mtk_resumedactivity_format(self) -> None:
        lib_text = read_text("scripts/lib/CameraFaceMetrics.ps1")
        match = re.search(r"(?s)function Get-ResumedActivityInfo \{(.*?)(?=\nfunction |\Z)", lib_text)
        self.assertIsNotNone(match, "Get-ResumedActivityInfo must exist")
        body = match.group(1)
        self.assertIn("mResumedActivity", body)
        self.assertIn("ResumedActivity", body)
        self.assertIn("topResumedActivity", body)
        self.assertRegex(body, r"mResumedActivity\|ResumedActivity\|topResumedActivity")

    def test_v222_preflight_retries_punch_enable_until_camera_becomes_active(self) -> None:
        lib_text = read_text("scripts/lib/CameraFaceMetrics.ps1")
        match = re.search(r"(?s)function Wait-CameraFaceReady \{(.*?)(?=\nfunction |\Z)", lib_text)
        self.assertIsNotNone(match, "Wait-CameraFaceReady must exist")
        body = match.group(1)
        self.assertIn("Enable-PunchIfNeeded", body)
        self.assertIn("toggle-attempt-", body)
        self.assertRegex(body, r"(?s)CameraActive.*?Enable-PunchIfNeeded.*?Get-CameraFaceHealthSample")

    def test_v224_camera_face_soak_build_is_transactional_and_auto_enables_punch(self) -> None:
        gradle = read_text("app/build.gradle")
        punch = read_text("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        policy = read_text("app/src/main/java/com/punch/app/fragment/PunchCameraPolicy.java")
        runner = read_text("scripts/run-camera-face-soak.ps1")

        self.assertIn("cameraFaceSoak", gradle)
        self.assertIn('versionNameSuffix "-camera-face-soak"', gradle)
        self.assertNotIn('applicationIdSuffix ".cameraFaceSoak"', gradle)
        self.assertTrue((ROOT / "app/src/cameraFaceSoak/res/values/test_flags.xml").exists())
        self.assertIn("camera_face_soak_auto_enable", punch)
        self.assertIn("shouldEnablePunchOnVisibleEntry", policy)
        self.assertIn("assembleCameraFaceSoak", runner)
        self.assertIn("Backup-InstalledPackageApks", runner)
        self.assertIn("Restore-InstalledPackageApks", runner)
        self.assertIn("production-backup", runner)
        self.assertIn("app-cameraFaceSoak.apk", runner)


    def test_v226_core_process_streams_output_and_returns_only_exit_code(self) -> None:
        runner = read_text("scripts/run-camera-face-soak.ps1")
        body = runner.split('function Invoke-CameraFaceCoreProcess', 1)[1].split('try {', 1)[0]
        self.assertIn('camera-face-soak-core.log', body)
        self.assertIn('Invoke-LoggedCommand', body)
        self.assertIn('-FilePath $powershellExe', body)
        self.assertIn('-Arguments $args', body)
        self.assertNotIn('& $powershellExe @args', body)

    def test_v225_camera_face_launches_through_exported_launcher_not_internal_main(self) -> None:
        common = read_text("scripts/lib/DeviceTestCommon.ps1")
        runner = read_text("scripts/run-camera-face-soak.ps1")
        core = read_text("scripts/run-camera-face-soak-core.ps1")
        camera_lib = read_text("scripts/lib/CameraFaceMetrics.ps1")

        self.assertIn("function Start-AndroidLauncherPackage", common)
        self.assertIn("android.intent.action.MAIN", common)
        self.assertIn("android.intent.category.LAUNCHER", common)
        self.assertRegex(common, r'(?s)Start-AndroidLauncherPackage.*?"-p".*?\$PackageName')
        self.assertNotIn('$PackageName/.activity.MainActivity', runner)
        self.assertNotIn('$PackageName/.activity.MainActivity', core)
        self.assertNotIn('$PackageName/.activity.MainActivity', camera_lib)
        self.assertIn("Start-AndroidLauncherPackage", runner)
        self.assertIn("Start-AndroidLauncherPackage", core)
        self.assertIn("Start-AndroidLauncherPackage", camera_lib)

    def test_v224_release_default_keeps_auto_enable_disabled(self) -> None:
        main_flag = read_text("app/src/main/res/values/test_flags.xml")
        soak_flag = read_text("app/src/cameraFaceSoak/res/values/test_flags.xml") if (ROOT / "app/src/cameraFaceSoak/res/values/test_flags.xml").exists() else ""
        self.assertRegex(main_flag, r'<bool name="camera_face_soak_auto_enable">false</bool>')
        self.assertRegex(soak_flag, r'<bool name="camera_face_soak_auto_enable">true</bool>')


    def test_v22_does_not_modify_android_app_sources(self) -> None:
        forbidden = [
            ROOT / "app" / "src" / "performanceTest",
            ROOT / "app" / "src" / "cameraFaceTest",
        ]
        for path in forbidden:
            self.assertFalse(path.exists(), f"V2.2 unexpectedly introduced app test source set: {path}")

    def test_v228_face_readiness_uses_test_only_runtime_receiver_not_proc_maps(self) -> None:
        camera_lib = read_text("scripts/lib/CameraFaceMetrics.ps1")
        manifest_path = ROOT / "app/src/cameraFaceSoak/AndroidManifest.xml"
        receiver_path = ROOT / "app/src/cameraFaceSoak/java/com/punch/app/receiver/CameraFaceSoakStatusReceiver.java"

        self.assertTrue(manifest_path.exists(), "cameraFaceSoak status receiver manifest overlay is missing")
        self.assertTrue(receiver_path.exists(), "cameraFaceSoak status receiver class is missing")

        manifest = manifest_path.read_text(encoding="utf-8-sig")
        receiver = receiver_path.read_text(encoding="utf-8-sig")

        self.assertIn("CameraFaceSoakStatusReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        self.assertNotIn("CameraFaceSoakStatusReceiver", read_text("app/src/main/AndroidManifest.xml"))
        self.assertNotIn("CameraFaceSoakStatusReceiver", read_text("app/src/smoke/AndroidManifest.xml"))
        self.assertIn("FaceManager.get().isInitialized()", receiver)
        self.assertIn("getLoadedFaceCount()", receiver)
        self.assertIn("setResultData", receiver)

        self.assertIn("function Get-CameraFaceRuntimeStatus", camera_lib)
        self.assertIn("CAMERA_FACE_SOAK_STATUS", camera_lib)
        self.assertIn("face_initialized", camera_lib)
        self.assertIn("FaceRuntimeInitialized", camera_lib)

        wait_body = camera_lib.split("function Wait-CameraFaceReady", 1)[1].split("function Find-CameraFaceHealthEvents", 1)[0]
        self.assertIn("FaceRuntimeInitialized", wait_body)
        self.assertNotRegex(wait_body, r"faceReady\s*=\s*\(-not \$last\.FaceSdkProbeSupported\)")


class FacePunchStressV23ContractTest(unittest.TestCase):
    def test_v23_variant_is_same_package_and_isolated(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)facePunchStress\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/facePunchStress/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("FacePunchStressReceiver", text)
        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml",
            "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml",
        ]:
            self.assertNotIn("FacePunchStressReceiver", read_text(relative))

    def test_v23_reuses_production_persistence_with_non_upload_stress_action(self) -> None:
        persistence = read_text("app/src/main/java/com/punch/app/service/PunchPersistence.java")
        fragment = read_text("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.assertIn("boolean persist", persistence)
        self.assertIn("enqueueSyncItem", persistence)
        self.assertIn("PunchPersistence", fragment)
        self.assertIn("ACTION_PUNCH_STRESS_NO_UPLOAD", constants)
        self.assertIn('"stress_punch_no_upload"', constants)

    def test_v23_database_has_prefix_count_and_cleanup_helpers(self) -> None:
        db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        for token in [
            "countPunchRecordsByClientPrefix",
            "countSyncQueueByAction",
            "deletePunchRecordsByClientPrefix",
            "deleteSyncQueueByAction",
        ]:
            self.assertIn(token, db)

    def test_v23_face_engine_uses_local_or_prepared_fixture_and_real_face_manager(self) -> None:
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        fixture = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java")
        combined = engine + fixture
        for token in [
            "getAllActiveEmployees",
            "faceRegistered",
            "FaceFileManager.getFaceImagePath",
            "BitmapFactory.decodeFile",
            "recognizeFromBitmap",
            "bitmap.recycle",
        ]:
            self.assertIn(token, combined)

    def test_v233_face_fixture_auto_prepares_without_marking_employee_registered(self) -> None:
        fixture_path = ROOT / "app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java"
        self.assertTrue(fixture_path.is_file(), fixture_path)
        fixture = fixture_path.read_text(encoding="utf-8")
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        for token in [
            "getAllActiveEmployees",
            "FaceManager.get().registerFace",
            "FaceManager.get().removeFace",
            "face_sdk_ids",
            "STRESS_FACE_V23",
            "bundled_test_face",
        ]:
            self.assertIn(token, fixture)
        self.assertNotIn("updateFaceRegistration", fixture)
        self.assertIn("FaceStressFixture.prepare", engine)
        self.assertIn("fixture.close()", engine)

    def test_v234_face_fixture_uses_bundled_public_domain_face_without_business_data_dependency(self) -> None:
        fixture = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressFixture.java")
        engine = read_text("app/src/facePunchStress/java/com/punch/app/stress/FaceStressEngine.java")
        result = read_text("app/src/facePunchStress/java/com/punch/app/stress/StressResult.java")
        asset = ROOT / "app/src/facePunchStress/res/raw/stress_face_fixture.jpg"
        self.assertTrue(asset.is_file(), asset)
        self.assertGreater(asset.stat().st_size, 20_000)
        for token in [
            "STRESS_FACE_V23",
            "R.raw.stress_face_fixture",
            "fixture_source",
            "bundled_test_face",
            "FaceManager.get().registerFace",
            "FaceManager.get().removeFace",
            "deleteFaceSdkMapping",
        ]:
            self.assertIn(token, fixture + engine + result)
        self.assertNotIn("FaceFileManager.downloadAndVerify", fixture)
        self.assertNotIn("faceImageUrl", fixture)
        self.assertNotIn("updateFaceRegistration", fixture)

    def test_v23_punch_engine_uses_stress_prefix_snapshot_and_non_upload_action(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/PunchStressEngine.java")
        for token in [
            "STRESS_V23_",
            "PunchSnapshotHelper.capture",
            "ACTION_PUNCH_STRESS_NO_UPLOAD",
            "PunchPersistence.persist",
        ]:
            self.assertIn(token, text)
        self.assertNotIn("ACTION_PUNCH_PUSH", text)
        self.assertIn("record.isSynced = 1", text)

    def test_v236_punch_engine_uses_synthetic_employee_without_business_employee_dependency(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/PunchStressEngine.java")
        self.assertIn('STRESS_EMP_V23', text)
        self.assertIn('V2.3 Stress Employee', text)
        self.assertIn('Stress', text)
        self.assertNotIn('getAllActiveEmployees', text)
        self.assertNotIn('List<Employee>', text)
        self.assertNotIn('import com.punch.app.model.Employee;', text)
        self.assertIn('record.empId = STRESS_EMP_ID;', text)
        self.assertIn('record.empName = STRESS_EMP_NAME;', text)
        self.assertIn('record.dept = STRESS_DEPT;', text)

    def test_v23_receiver_exposes_run_status_cleanup_only_in_test_variant(self) -> None:
        text = read_text("app/src/facePunchStress/java/com/punch/app/stress/FacePunchStressReceiver.java")
        for token in [
            "ACTION_RUN",
            "ACTION_STATUS",
            "ACTION_CLEANUP",
            "MODE_FACE",
            "MODE_PUNCH",
            "MODE_ALL",
        ]:
            self.assertIn(token, text)
        self.assertIn("Executors.newSingleThreadExecutor", text)

    def test_v23_runner_is_transactional_and_reports_face_punch_metrics(self) -> None:
        runner = read_text("scripts/run-face-punch-stress.ps1")
        lib = read_text("scripts/lib/FacePunchStress.ps1")
        for token in [
            "assembleFacePunchStress",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "production-backup",
            "FacePunchStressReceiver",
            "Mode",
            "Count",
            "metrics.csv",
            "summary.txt",
            "fatal-events.txt",
        ]:
            self.assertTrue(token in runner or token in lib, token)
        self.assertNotRegex(runner + lib, r"\$PID\b")

    def test_v232_face_preflight_waits_for_punch_data_preparation(self) -> None:
        receiver = read_text("app/src/facePunchStress/java/com/punch/app/stress/FacePunchStressReceiver.java")
        lib = read_text("scripts/lib/FacePunchStress.ps1")
        self.assertIn("PunchApplication.get()", receiver)
        self.assertIn("isPunchDataPreparing()", receiver)
        self.assertIn("isPunchRecognitionReady()", receiver)
        self.assertIn("punch_data_preparing", receiver)
        self.assertIn("punch_data_ready", receiver)
        self.assertIn("PunchDataPreparing", lib)
        runner = read_text("scripts/run-face-punch-stress.ps1")
        self.assertIn("PreflightTimeoutSeconds", runner)
        self.assertIn("Face data preparation did not finish", runner)
        wait_body = lib.split("function Wait-FacePunchStressReady", 1)[1].split("function Convert-StressJsonToCsv", 1)[0]
        self.assertRegex(wait_body, r"FaceInitialized.*-and.*-not \$s\.PunchDataPreparing")

    def test_v235_face_native_growth_uses_hot_baseline_after_warmup(self) -> None:
        runner = read_text("scripts/run-face-punch-stress.ps1")
        self.assertIn("WarmupFaceCount", runner)
        self.assertIn("[INFO] Face native warm-up", runner)
        self.assertIn("native_heap_cold_to_warm_percent", runner)
        warmup_pos = runner.index("[INFO] Face native warm-up")
        baseline_pos = runner.index("$initial=Get-AppPerformanceSample")
        run_pos = runner.index("$runOutput=Invoke-FacePunchStressBroadcast")
        self.assertLess(warmup_pos, baseline_pos)
        self.assertLess(baseline_pos, run_pos)
        self.assertRegex(runner, r"nativeGrowth=100\.0\*\(\$final\.NativeHeapMb-\$initial\.NativeHeapMb\)/\$initial\.NativeHeapMb")

    def test_v23_documentation_exists(self) -> None:
        path = ROOT / "FACE-PUNCH-STRESS-V2.3.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in ["-Count 10", "-Count 100", "-Count 500", "-Count 1000", "Mode Face", "Mode Punch"]:
            self.assertIn(token, text)


class FacePunchStressSafetyContractTest(unittest.TestCase):
    def test_v23_precheck_bypass_is_resource_gated_and_false_in_release(self) -> None:
        main_flags = read_text("app/src/main/res/values/test_flags.xml")
        stress_flags = read_text("app/src/facePunchStress/res/values/test_flags.xml")
        face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">false</bool>', main_flags)
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">true</bool>', stress_flags)
        self.assertIn("R.bool.face_punch_stress_bypass_prechecks", face)

    def test_v23_sync_coordinator_never_uploads_stress_action(self) -> None:
        sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn("Constants.ACTION_PUNCH_PUSH", sync)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", sync)


class NetworkFaultSyncV25ContractTest(unittest.TestCase):
    def test_v25_variant_is_same_package_and_receiver_is_test_only(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)networkFaultTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/networkFaultTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("NetworkFaultTestReceiver", text)
        self.assertIn('android:exported="true"', text)
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)
        app = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestApplication.java")
        self.assertIn("setUiTestModeForTest(true)", app)
        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml",
            "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml",
            "app/src/facePunchStress/AndroidManifest.xml",
        ]:
            self.assertNotIn("NetworkFaultTestReceiver", read_text(relative))

    def test_v25_fault_controller_uses_existing_api_client_test_hooks_and_loopback(self) -> None:
        controller = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultController.java")
        server = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultServer.java")
        self.assertIn("ApiClient.setBaseUrlForTest", controller)
        self.assertIn("ApiClient.setClientForTest", controller)
        self.assertIn("ApiClient.resetForTest", controller)
        self.assertIn("127.0.0.1", controller + server)
        for token in ["SUCCESS", "HTTP_401", "HTTP_500", "TIMEOUT", "DROP"]:
            self.assertIn(token, server)
        self.assertIn("new ServerSocket", server)

    def test_v25_uses_isolated_sqlite_database_before_production_sync(self) -> None:
        db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        bridge = read_text("app/src/networkFaultTest/java/com/punch/app/db/NetworkFaultDatabaseController.java")
        self.assertIn("databaseNameOverrideForTest", db)
        self.assertIn("setDatabaseNameForTest", db)
        self.assertIn("resetForTest", db)
        self.assertIn("punch_network_fault_v25.db", bridge)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest", bridge)
        self.assertIn("deleteDatabase", bridge)
        self.assertNotIn("Constants.DB_NAME", bridge)

    def test_v25_engine_drives_production_sync_with_real_unsynced_punch_queue(self) -> None:
        engine = read_text("app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestEngine.java")
        for token in [
            "NET_V25_",
            "record.isSynced = 0",
            "Constants.ACTION_PUNCH_PUSH",
            "SyncService.triggerSync",
            "SyncTrigger.AFTER_PUNCH",
            "SyncTrigger.MANUAL",
            "SYNC_MAX_RETRY",
        ]:
            self.assertIn(token, engine)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", engine)
        self.assertIn("client_record_id LIKE ?", engine)

    def test_v251_runner_foregrounds_test_host_before_sync_broadcasts(self) -> None:
        manifest = read_text("app/src/networkFaultTest/AndroidManifest.xml")
        runner = read_text("scripts/run-network-fault-sync.ps1")
        host = ROOT / "app/src/networkFaultTest/java/com/punch/app/network/NetworkFaultTestHostActivity.java"
        self.assertTrue(host.is_file(), host)
        self.assertIn("NetworkFaultTestHostActivity", manifest)
        self.assertIn('android:exported="true"', manifest)
        text = host.read_text(encoding="utf-8")
        self.assertIn("FLAG_KEEP_SCREEN_ON", text)
        self.assertIn("FLAG_TURN_SCREEN_ON", text)
        self.assertIn("NetworkFaultTestHostActivity", runner)
        self.assertLess(runner.index("NetworkFaultTestHostActivity"), runner.index("Invoke-NetworkFaultBroadcast"))

    def test_v25_runner_is_transactional_and_restores_network_state(self) -> None:
        runner = read_text("scripts/run-network-fault-sync.ps1")
        lib = read_text("scripts/lib/NetworkFaultSync.ps1")
        combined = runner + lib
        for token in [
            "assembleNetworkFaultTest",
            "Backup-InstalledPackageApks",
            "Restore-InstalledPackageApks",
            "NetworkFaultTestReceiver",
            "DeviceOfflineRecovery",
            "Capture-DeviceNetworkState",
            "Restore-DeviceNetworkState",
            "summary.txt",
            "fatal-events.txt",
        ]:
            self.assertIn(token, combined)
        self.assertIn("finally", runner)
        self.assertNotIn("Constants.DEFAULT_BASE_URL", combined)

    def test_v25_powershell_files_are_windows_ps51_safe(self) -> None:
        for relative in ["scripts/lib/NetworkFaultSync.ps1", "scripts/run-network-fault-sync.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)

    def test_v25_documentation_exists_and_lists_required_scenarios(self) -> None:
        path = ROOT / "NETWORK-FAULT-SYNC-V2.5.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "DisconnectRecovery", "TimeoutRecovery", "Http500Retry",
            "Http401Recovery", "RetryLimitManualRecovery", "DeviceOfflineRecovery",
        ]:
            self.assertIn(token, text)


class DbStressV26ContractTest(unittest.TestCase):
    def test_v26_variant_is_same_package_and_uses_isolated_database(self) -> None:
        gradle = read_text("app/build.gradle")
        block = re.search(r"(?s)dbStressTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))
        manifest = ROOT / "app/src/dbStressTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        text = manifest.read_text(encoding="utf-8")
        self.assertIn("DbStressTestHostActivity", text)
        self.assertIn("DbStressTestReceiver", text)
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)
        controller = read_text("app/src/dbStressTest/java/com/punch/app/db/DbStressDatabaseController.java")
        app = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestApplication.java")
        self.assertIn('punch_db_stress_v26.db', controller)
        self.assertIn('setDatabaseNameForTest', controller)
        self.assertIn('deleteDatabase', controller)
        self.assertIn('setUiTestModeForTest(true)', app)
        for relative in [
            "app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml",
            "app/src/smoke/AndroidManifest.xml", "app/src/deviceOwnerTest/AndroidManifest.xml",
            "app/src/cameraFaceSoak/AndroidManifest.xml", "app/src/facePunchStress/AndroidManifest.xml",
            "app/src/networkFaultTest/AndroidManifest.xml",
        ]:
            self.assertNotIn("DbStressTestReceiver", read_text(relative))

    def test_v26_application_installs_safe_loopback_before_super_startup(self) -> None:
        app = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestApplication.java")
        self.assertIn("DbStressNetworkController.reset()", app)
        self.assertLess(app.index("DbStressNetworkController.reset()"), app.index("super.onCreate()"))

    def test_v26_loopback_server_is_production_sync_compatible(self) -> None:
        controller = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressNetworkController.java")
        server = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressServer.java")
        self.assertIn("ApiClient.setBaseUrlForTest", controller)
        self.assertIn("ApiClient.setClientForTest", controller)
        self.assertIn("SAFE_IDLE_BASE_URL", controller)
        self.assertIn("127.0.0.1", controller + server)
        self.assertIn("new ServerSocket", server)
        self.assertIn("/clock/upload", server)
        self.assertIn("v26_heartbeat_suppressed", server)
        self.assertIn("getPunchRequestCount", server)
        self.assertNotIn("DEFAULT_BASE_URL", controller + server)

    def test_v26_network_controller_never_restores_production_base_url_in_test_process(self) -> None:
        controller = read_text("app/src/dbStressTest/java/com/punch/app/network/DbStressNetworkController.java")
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', controller)
        self.assertIn("ApiClient.setBaseUrlForTest(SAFE_IDLE_BASE_URL)", controller)
        self.assertNotIn("ApiClient.resetForTest()", controller)

    def test_v26_engine_uses_real_queue_restart_integrity_and_vacuum(self) -> None:
        engine = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestEngine.java")
        receiver = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestReceiver.java")
        combined = engine + receiver
        for token in [
            "DB_V26_", "record.isSynced = 0", "Constants.ACTION_PUNCH_PUSH",
            "SyncService.triggerSync", "SyncTrigger.MANUAL", "PUNCH_BATCH_SIZE",
            "PRAGMA integrity_check", "VACUUM", "Process.killProcess",
            "1000", "5000", "10000", "duplicate_client_ids",
            "unexpected_retry", "getPunchRequestCount",
        ]:
            self.assertIn(token, combined)
        for action in ["PREPARE", "KILL", "VERIFY_RESTART", "DRAIN", "STATUS", "CLEANUP"]:
            self.assertIn(action, receiver)
        self.assertNotIn("ACTION_PUNCH_STRESS_NO_UPLOAD", engine)
        self.assertNotIn("PunchSnapshotHelper.capture", engine)

    def test_v26_runner_is_transactional_restart_aware_and_ps51_safe(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        lib = read_text("scripts/lib/DbStress.ps1")
        combined = runner + lib
        for token in [
            "assembleDbStressTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "DbStressTestHostActivity", "PREPARE", "KILL", "VERIFY_RESTART", "DRAIN",
            "1000", "5000", "10000", "metrics.csv", "summary.txt", "fatal-events.txt",
            "Get-AppPerformanceSample", "finally",
        ]:
            self.assertIn(token, combined)
        self.assertLess(runner.index("DbStressTestHostActivity"), runner.index("Invoke-DbStressBroadcast"))
        self.assertNotIn("DEFAULT_BASE_URL", combined)
        for relative in ["scripts/lib/DbStress.ps1", "scripts/run-db-stress.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)

    def test_v261_runner_requires_actual_kiosk_restore_gate(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        lib = read_text("scripts/lib/DbStress.ps1")
        self.assertIn("Wait-DbStressProductionKioskReady", runner)
        self.assertIn("Get-DbStressProductionKioskState", lib)
        self.assertIn("dumpsys", lib)
        self.assertIn("activity", lib)
        self.assertIn("dpm", lib)
        self.assertIn("list-owners", lib)
        self.assertRegex(lib, r"mResumedActivity|topResumedActivity|ResumedActivity")
        self.assertRegex(lib, r"mLockTaskModeState|lockTaskModeState")
        self.assertIn("MainActivity", lib)
        self.assertIn("kiosk_restore_ready", runner)
        self.assertIn("restore-kiosk-state.txt", runner)
        self.assertIn("core_status=", runner)
        self.assertIn("overall_status=", runner)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))

    def test_v262_runner_terminates_test_process_before_restoring_production_apk(self) -> None:
        runner = read_text("scripts/run-db-stress.ps1")
        receiver = read_text("app/src/dbStressTest/java/com/punch/app/dbstress/DbStressTestReceiver.java")
        self.assertIn("ACTION_TERMINATE", receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("TERMINATE", runner)
        self.assertLess(runner.index("TERMINATE"), runner.index("Restore-InstalledPackageApks"))

    def test_v26_powershell_does_not_shadow_readonly_pid_automatic_variable(self) -> None:
        for relative in ["scripts/lib/DbStress.ps1", "scripts/run-db-stress.ps1"]:
            text = read_text(relative)
            self.assertIsNone(
                re.search(r"(?im)^\s*\$pid\s*=", text),
                f"{relative} must not assign to PowerShell's readonly $PID automatic variable",
            )

    def test_v26_documentation_exists_with_three_capacity_commands(self) -> None:
        path = ROOT / "DB-STRESS-V2.6.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Count 1000", "-Count 5000", "-Count 10000",
            "punch_db_stress_v26.db", "PRAGMA integrity_check", "VACUUM",
            "PUNCH_BATCH_SIZE", "production database", "no snapshots",
        ]:
            self.assertIn(token, text)


class StressResultAndroidCompileRegressionTest(unittest.TestCase):
    def test_stress_result_compiles_against_android_json_overloads(self) -> None:
        import subprocess
        import tempfile
        import textwrap
        import shutil

        javac = shutil.which("javac")
        self.assertIsNotNone(javac, "javac is required for this regression test")
        source = ROOT / "app/src/facePunchStress/java/com/punch/app/stress/StressResult.java"
        self.assertTrue(source.is_file(), source)

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            json_dir = root / "org/json"
            json_dir.mkdir(parents=True)
            (json_dir / "JSONException.java").write_text(
                "package org.json; public class JSONException extends Exception {}",
                encoding="utf-8",
            )
            (json_dir / "JSONArray.java").write_text(textwrap.dedent("""
                package org.json;
                public class JSONArray {
                    public JSONArray put(Object value) { return this; }
                }
            """), encoding="utf-8")
            (json_dir / "JSONObject.java").write_text(textwrap.dedent("""
                package org.json;
                public class JSONObject {
                    public JSONObject put(String key, int value) throws JSONException { return this; }
                    public JSONObject put(String key, long value) throws JSONException { return this; }
                    public JSONObject put(String key, Object value) throws JSONException { return this; }
                }
            """), encoding="utf-8")
            result = subprocess.run(
                [javac, "-source", "8", "-target", "8", "-d", str(root / "out"),
                 str(json_dir / "JSONException.java"), str(json_dir / "JSONArray.java"),
                 str(json_dir / "JSONObject.java"), str(source)],
                text=True, capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

class ProcessRecoveryV271ContractTest(unittest.TestCase):
    def test_v271_variant_is_same_package_and_runs_production_lifecycle(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertIn("processRecoveryTest", gradle)
        block = re.search(r"(?s)processRecoveryTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))

        manifest = ROOT / "app/src/processRecoveryTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        root = ET.parse(manifest).getroot()
        app = root.find("application")
        self.assertIsNotNone(app)
        self.assertEqual(
            ".processrecovery.ProcessRecoveryTestApplication",
            app.attrib.get(ANDROID_NS + "name"),
        )

        application = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestApplication.java"
        )
        self.assertNotIn("setUiTestModeForTest(true)", application)
        self.assertIn("setUiTestModeForTest(false)", application)
        self.assertIn("ProcessRecoveryDatabaseController.activateExisting(this)", application)
        self.assertIn("ProcessRecoveryNetworkController.reset()", application)
        self.assertLess(
            application.index("ProcessRecoveryDatabaseController.activateExisting(this)"),
            application.index("super.onCreate()"),
        )
        self.assertLess(
            application.index("ProcessRecoveryNetworkController.reset()"),
            application.index("super.onCreate()"),
        )

    def test_v271_runner_cold_starts_test_variant_before_preflight(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        install_pos = runner.index("Installing temporary processRecoveryTest build")
        preflight_start = runner.index("Starting V2.7.1 Kiosk entry for preflight")
        transition = runner[install_pos:preflight_start]
        self.assertIn("-Action TERMINATE", transition)
        self.assertIn("Wait-ProcessRecoveryOldPidExit", transition)
        self.assertIn("cold", transition.lower())
        self.assertIn("$preflight.DatabaseActive", runner)

    def test_v271_uses_isolated_database_and_loopback_only_network(self) -> None:
        db = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/db/ProcessRecoveryDatabaseController.java"
        )
        network = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryNetworkController.java"
        )
        self.assertIn('TEST_DB_NAME = "punch_process_recovery_v271.db"', db)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME)", db)
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', network)
        self.assertIn("ApiClient.setBaseUrlForTest", network)
        self.assertNotRegex(network, r"https?://(?!127\.0\.0\.1)")

    def test_v271_manifest_removes_update_install_receiver(self) -> None:
        text = read_text("app/src/processRecoveryTest/AndroidManifest.xml")
        self.assertIn("UpdateInstallStateReceiver", text)
        self.assertIn('tools:node="remove"', text)

    def test_v271_engine_seeds_real_punch_queue_and_uses_real_sync_service(self) -> None:
        engine = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestEngine.java"
        )
        self.assertIn('PREFIX = "PROC_V271_"', engine)
        self.assertIn("SYNC_RECORD_COUNT = 100", engine)
        self.assertIn("PunchRecord record = new PunchRecord()", engine)
        self.assertIn("db.insertPunchRecord(record)", engine)
        self.assertIn("db.enqueueSyncItem(record.clientRecordId, Constants.ACTION_PUNCH_PUSH)", engine)
        self.assertIn("SyncService.triggerSync(context, SyncTrigger.MANUAL)", engine)
        self.assertIn('rawQuery("PRAGMA integrity_check"', engine)
        self.assertIn("duplicateClientIds", engine)
        self.assertIn("queueCount == state.unsynced", engine)
        self.assertIn("synced > 0 && synced < SYNC_RECORD_COUNT", engine)

    def test_v271_slow_loopback_server_creates_partial_sync_window(self) -> None:
        server = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryServer.java"
        )
        controller = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/network/ProcessRecoveryNetworkController.java"
        )
        self.assertIn("punchDelayMs", server)
        self.assertIn("Thread.sleep(punchDelayMs)", server)
        self.assertIn('/clock/upload', server)
        self.assertIn('/device/heartbeat', server)
        self.assertIn("startLoopback(long punchDelayMs)", controller)
        self.assertIn("127.0.0.1", controller)

    def test_v271_receiver_is_test_only_and_exposes_required_kill_recovery_actions(self) -> None:
        manifest = read_text("app/src/processRecoveryTest/AndroidManifest.xml")
        receiver = read_text(
            "app/src/processRecoveryTest/java/com/punch/app/processrecovery/ProcessRecoveryTestReceiver.java"
        )
        self.assertIn("ProcessRecoveryTestReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        for action in [
            "STATUS", "PREPARE_SYNC_KILL", "KILL_FOREGROUND",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "CLEANUP", "TERMINATE",
        ]:
            self.assertIn(action, receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("sync-kill-checkpoint.json", receiver)
        self.assertIn("sync-after-restart.json", receiver)
        self.assertIn("sync-result.json", receiver)
        self.assertIn("foreground-kill.json", receiver)
        self.assertIn("kill-events.jsonl", receiver)

        for relative in [
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "app/src/release/AndroidManifest.xml" if (ROOT / "app/src/release/AndroidManifest.xml").exists() else "app/src/main/AndroidManifest.xml",
        ]:
            self.assertNotIn("ProcessRecoveryTestReceiver", read_text(relative))

    def test_v271_runner_observes_autonomous_recovery_without_starting_activity_after_kill(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        lib = read_text("scripts/lib/ProcessRecovery.ps1")
        combined = runner + lib
        for token in [
            "assembleProcessRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "ForegroundKill", "SyncKillRecovery", "KILL_FOREGROUND", "PREPARE_SYNC_KILL",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "TERMINATE", "Wait-ProcessRecoveryAutomaticKioskReady",
            "old_pid", "new_pid", "restore-kiosk-state.txt", "fatal-events.txt", "finally",
        ]:
            self.assertIn(token, combined)
        wait_body = lib.split("function Wait-ProcessRecoveryAutomaticKioskReady", 1)[1]
        if "function " in wait_body:
            wait_body = wait_body.split("function ", 1)[0]
        self.assertNotIn("am','start", wait_body)
        self.assertNotIn('am","start', wait_body)
        self.assertNotIn("force-stop", combined)
        self.assertNotRegex(combined, r"(?im)^\s*\$pid\s*=")
        self.assertIn("dpm", lib)
        self.assertIn("list-owners", lib)
        self.assertRegex(lib, r"mResumedActivity|topResumedActivity|ResumedActivity")
        self.assertRegex(lib, r"mLockTaskModeState|lockTaskModeState")

    def test_v271_runner_requires_new_pid_and_autonomous_kiosk_before_sync_restart_verification(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        self.assertIn("OldPid", runner)
        self.assertIn("NewPid", runner)
        self.assertRegex(runner, r"(?s)KILL_FOREGROUND.*?Wait-ProcessRecoveryAutomaticKioskReady")
        self.assertRegex(runner, r"(?s)PREPARE_SYNC_KILL.*?Wait-ProcessRecoveryAutomaticKioskReady.*?VERIFY_SYNC_RESTART")
        self.assertLess(runner.index("Wait-ProcessRecoveryAutomaticKioskReady"), runner.index("VERIFY_SYNC_RESTART"))
        self.assertIn("autonomous_recovery", runner)

    def test_v271_runner_preserves_device_kill_evidence_and_delays_final_result_until_restore(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        self.assertIn("kill-events.jsonl", runner)
        self.assertIn("foreground-kill.json", runner)
        self.assertIn("foreground-autonomous-recovery.txt", runner)
        self.assertIn("Get-ProcessRecoveryTestFile", runner)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))

    def test_v271_powershell_is_ps51_compatible_and_loopback_only(self) -> None:
        for relative in ["scripts/lib/ProcessRecovery.ps1", "scripts/run-process-recovery.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)
            text = raw.decode("utf-8-sig")
            self.assertNotRegex(text, r"https?://(?!127\.0\.0\.1)")
            self.assertIsNone(re.search(r"(?im)^\s*\$pid\s*=", text))

    def test_v271_documentation_exists_with_commands_and_autonomous_recovery_definition(self) -> None:
        path = ROOT / "PROCESS-RECOVERY-V2.7.1.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Scenario ForegroundKill", "-Scenario SyncKillRecovery", "-Scenario All",
            "punch_process_recovery_v271.db", "PROC_V271_", "127.0.0.1",
            "autonomous", "LockTask", "Device Owner", "production backend",
        ]:
            self.assertIn(token, text)

    def test_v271_runner_observes_autonomous_recovery_and_restores_production_transactionally(self) -> None:
        runner = read_text("scripts/run-process-recovery.ps1")
        lib = read_text("scripts/lib/ProcessRecovery.ps1")
        for token in [
            "assembleProcessRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "ForegroundKill", "SyncKillRecovery", "KILL_FOREGROUND", "PREPARE_SYNC_KILL",
            "VERIFY_SYNC_RESTART", "RESUME_SYNC", "Wait-ProcessRecoveryAutomaticKioskReady",
            "Find-AppFatalEvents", "TERMINATE", "RESULT: PASS",
        ]:
            self.assertIn(token, runner + "\n" + lib)
        self.assertIn("old_pid", runner)
        self.assertIn("new_pid", runner)
        self.assertIn("Wait-ProcessRecoveryOldPidExit", runner)
        self.assertIn("Wait-ProcessRecoveryProductionKioskReady", runner)

        kill_pos = runner.index("-Action KILL_FOREGROUND")
        auto_pos = runner.index("Wait-ProcessRecoveryAutomaticKioskReady", kill_pos)
        self.assertNotIn("'am','start'", runner[kill_pos:auto_pos])
        self.assertNotIn('"am","start"', runner[kill_pos:auto_pos])

    def test_v271_powershell_avoids_pid_shadowing_and_non_loopback_targets(self) -> None:
        for relative in ["scripts/lib/ProcessRecovery.ps1", "scripts/run-process-recovery.ps1"]:
            text = read_text(relative)
            self.assertIsNone(re.search(r"(?i)\$pid\b", text), f"{relative} shadows PowerShell $PID")
            urls = re.findall(r"https?://[^'\"\s]+", text)
            self.assertTrue(all("127.0.0.1" in url for url in urls), (relative, urls))

if __name__ == "__main__":
    unittest.main(verbosity=2)


class CameraFaceRecoveryV273ContractTest(unittest.TestCase):
    def test_v273_variant_is_same_package_and_keeps_production_lifecycle(self) -> None:
        gradle = read_text("app/build.gradle")
        self.assertIn("cameraFaceRecoveryTest", gradle)
        block = re.search(r"(?s)cameraFaceRecoveryTest\s*\{(.*?)\n\s*\}", gradle)
        self.assertIsNotNone(block)
        self.assertIn("initWith debug", block.group(1))
        self.assertNotIn("applicationIdSuffix", block.group(1))

        manifest = ROOT / "app/src/cameraFaceRecoveryTest/AndroidManifest.xml"
        self.assertTrue(manifest.is_file(), manifest)
        app = ET.parse(manifest).getroot().find("application")
        self.assertIsNotNone(app)
        self.assertEqual(
            ".camerafacerecovery.CameraFaceRecoveryTestApplication",
            app.attrib.get(ANDROID_NS + "name"),
        )

        application = read_text(
            "app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestApplication.java"
        )
        self.assertIn("CameraFaceRecoveryDatabaseController.activateExisting(this)", application)
        self.assertIn("CameraFaceRecoveryNetworkController.reset()", application)
        self.assertIn("setUiTestModeForTest(false)", application)
        self.assertLess(application.index("CameraFaceRecoveryDatabaseController.activateExisting(this)"), application.index("super.onCreate()"))
        self.assertLess(application.index("CameraFaceRecoveryNetworkController.reset()"), application.index("super.onCreate()"))

    def test_v273_blocks_background_update_before_production_application_start(self) -> None:
        application = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestApplication.java")
        guard = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryUpdateGuard.java")
        self.assertIn("CameraFaceRecoveryUpdateGuard.block()", application)
        self.assertLess(application.index("CameraFaceRecoveryUpdateGuard.block()"), application.index("super.onCreate()"))
        self.assertIn('getDeclaredField("AUTO_UPDATE_RUNNING")', guard)
        self.assertIn("AtomicBoolean", guard)
        self.assertIn("set(true)", guard)

    def test_v273_isolated_database_loopback_and_test_flags(self) -> None:
        db = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/db/CameraFaceRecoveryDatabaseController.java")
        net = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/network/CameraFaceRecoveryNetworkController.java")
        flags = read_text("app/src/cameraFaceRecoveryTest/res/values/test_flags.xml")
        self.assertIn('TEST_DB_NAME = "punch_camera_face_recovery_v273.db"', db)
        self.assertIn("DatabaseHelper.setDatabaseNameForTest(TEST_DB_NAME)", db)
        self.assertIn('SAFE_IDLE_BASE_URL = "http://127.0.0.1:1"', net)
        self.assertIn("ApiClient.setBaseUrlForTest", net)
        self.assertNotRegex(net, r"https?://(?!127\.0\.0\.1)")
        self.assertIn('<bool name="camera_face_soak_auto_enable">true</bool>', flags)
        self.assertIn('<bool name="face_punch_stress_bypass_prechecks">true</bool>', flags)

    def test_v273_fixture_uses_normal_face_file_and_production_rebuild_path(self) -> None:
        fixture = ROOT / "app/src/cameraFaceRecoveryTest/res/raw/recovery_face_fixture.jpg"
        self.assertTrue(fixture.is_file(), fixture)
        self.assertGreater(fixture.stat().st_size, 1000)
        engine = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryEngine.java")
        for token in [
            'EMP_ID = "RECOVERY_FACE_V273"',
            "FaceFileManager.getFaceImagePath",
            "db.upsertEmployee(employee)",
            "app.resetPunchRecognitionState()",
            "app.preparePunchRecognitionData()",
            "FaceManager.get().getLoadedFaceCount()",
            "FaceManager.get().recognizeFromBitmap(bitmap)",
        ]:
            self.assertIn(token, engine)
        self.assertNotIn("pushPersonById", engine)
        self.assertNotIn("registerFace(", engine)

    def test_v273_prepare_refuses_to_race_existing_punch_preparation(self) -> None:
        engine = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryEngine.java")
        self.assertIn('throw new IllegalStateException("previous_preparation_still_running")', engine)
        self.assertRegex(engine, r"(?s)waitForPreviousPreparation.*?if \(!app\.isPunchDataPreparing\(\)\) return;.*?previous_preparation_still_running")

    def test_v273_receiver_exposes_prepare_health_recognize_kill_cleanup_terminate(self) -> None:
        manifest = read_text("app/src/cameraFaceRecoveryTest/AndroidManifest.xml")
        receiver = read_text("app/src/cameraFaceRecoveryTest/java/com/punch/app/camerafacerecovery/CameraFaceRecoveryTestReceiver.java")
        self.assertIn("CameraFaceRecoveryTestReceiver", manifest)
        self.assertIn('android:exported="true"', manifest)
        for token in ["STATUS", "PREPARE", "RECOGNIZE", "KILL", "CLEANUP", "TERMINATE"]:
            self.assertIn(token, receiver)
        self.assertIn("Process.killProcess(Process.myPid())", receiver)
        self.assertIn("kill-events.jsonl", receiver)
        self.assertIn("cycle-", receiver)
        for relative in ["app/src/main/AndroidManifest.xml", "app/src/debug/AndroidManifest.xml"]:
            self.assertNotIn("CameraFaceRecoveryTestReceiver", read_text(relative))

    def test_v273_runner_observes_autonomous_recovery_and_gates_camera_face(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        lib = read_text("scripts/lib/CameraFaceRecovery.ps1")
        combined = runner + "\n" + lib
        for token in [
            "assembleCameraFaceRecoveryTest", "Backup-InstalledPackageApks", "Restore-InstalledPackageApks",
            "-Cycles", "PREPARE", "RECOGNIZE", "KILL", "TERMINATE",
            "Wait-CameraFaceRecoveryAutomaticReady", "dumpsys", "media.camera",
            "FaceInitialized", "LoadedFaceCount", "PunchReady", "old_pid", "new_pid",
            "restore-kiosk-state.txt", "fatal-events.txt", "finally", "RESULT: PASS",
        ]:
            self.assertIn(token, combined)
        wait_body = lib.split("function Wait-CameraFaceRecoveryAutomaticReady", 1)[1]
        if "function " in wait_body:
            wait_body = wait_body.split("function ", 1)[0]
        for forbidden in ["am','start", 'am","start', "monkey", "force-stop"]:
            self.assertNotIn(forbidden, wait_body)
        self.assertGreater(runner.rindex("RESULT:"), runner.index("finally"))
        self.assertNotRegex(combined, r"(?im)^\s*\$pid\s*=")
        self.assertNotRegex(combined, r"https?://(?!127\.0\.0\.1)")

    def test_v273_runner_has_cold_test_transition_and_final_cold_production_restore(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        install_pos = runner.index("Installing temporary cameraFaceRecoveryTest build")
        preflight_pos = runner.index("Starting V2.7.3 Kiosk entry for preflight")
        transition = runner[install_pos:preflight_pos]
        self.assertIn("-Action TERMINATE", transition)
        self.assertIn("Wait-CameraFaceRecoveryOldPidExit", transition)
        self.assertIn("DatabaseActive", runner)
        self.assertRegex(runner, r"(?s)finally.*?CLEANUP.*?TERMINATE.*?Restore-InstalledPackageApks")

    def test_v273_powershell_is_ps51_compatible_utf8_bom_crlf(self) -> None:
        for relative in ["scripts/lib/CameraFaceRecovery.ps1", "scripts/run-camera-face-recovery.ps1"]:
            raw = (ROOT / relative).read_bytes()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), relative)
            body = raw[3:]
            self.assertIn(b"\r\n", body, relative)
            self.assertNotIn(b"\n", body.replace(b"\r\n", b""), relative)
            self.assertIsNone(re.search(r"(?im)^\s*\$pid\s*=", raw.decode("utf-8-sig")))


    def test_v2731_runner_avoids_ps51_colon_interpolation_and_balances_terminate_call(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        self.assertNotRegex(runner, r'\$[A-Za-z_][A-Za-z0-9_]*:')
        self.assertIn('-Cycle ([Math]::Max(1,$completedCycles)))', runner)

    def test_v2732_preflight_preserves_startup_failure_evidence(self) -> None:
        runner = read_text("scripts/run-camera-face-recovery.ps1")
        lib = read_text("scripts/lib/CameraFaceRecovery.ps1")
        clear_pos = runner.index("logcat','-c")
        start_pos = runner.index("Starting V2.7.3 Kiosk entry for preflight")
        self.assertLess(clear_pos, start_pos)
        preflight_gate = runner.index("V2.7.3 preflight kiosk gate failed")
        self.assertIn("Save-CameraFaceRecoveryPreflightDiagnostics", runner[:preflight_gate + 500])
        self.assertIn("preflight-startup-logcat.txt", runner)
        self.assertIn("preflight-fatal-events.txt", runner)
        self.assertIn("process exited during preflight", runner)
        for token in [
            "dumpsys','activity','top",
            "dumpsys','window','windows",
            "dumpsys','power",
            "preflight-activity-top.txt",
            "preflight-window.txt",
            "preflight-power.txt",
        ]:
            self.assertIn(token, lib)
        self.assertIn("ProcessId", lib)
        self.assertIn("IsProcessRunning", lib)

    def test_v2731_runner_parses_with_powershell_when_available(self) -> None:
        import shutil
        import subprocess
        gate = ROOT / "scripts/tests/test-powershell-parse.ps1"
        self.assertTrue(gate.is_file(), gate)
        gate_text = gate.read_text(encoding="utf-8-sig")
        self.assertIn("System.Management.Automation.Language.Parser]::ParseFile", gate_text)
        self.assertIn("PowerShell parser errors=0", gate_text)
        exe = shutil.which("pwsh") or shutil.which("powershell")
        if not exe:
            self.skipTest("PowerShell parser executable is not available in this environment")
        script = ROOT / "scripts/run-camera-face-recovery.ps1"
        command = [exe, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(gate), "-Paths", str(script)]
        completed = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)

    def test_v273_docs_define_three_cycles_and_real_post_restart_recognition(self) -> None:
        path = ROOT / "CAMERA-FACE-RECOVERY-V2.7.3.md"
        self.assertTrue(path.is_file(), path)
        text = path.read_text(encoding="utf-8")
        for token in [
            "-Cycles 1", "-Cycles 3", "RECOVERY_FACE_V273",
            "punch_camera_face_recovery_v273.db", "127.0.0.1",
            "autonomous", "Camera", "Face", "recognizeFromBitmap", "LockTask", "Device Owner",
        ]:
            self.assertIn(token, text)

class FaceRtBgSdkIsolationContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sdk = read_text("app/src/main/java/com/punch/app/face/FaceSDKManager.java")
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_face_sdk_manager_owns_dedicated_background_instance_and_engines(self) -> None:
        for token in [
            "import com.baidu.idl.main.facesdk.model.BDFaceInstance;",
            "private BDFaceInstance backgroundFaceInstance;",
            "private FaceDetect backgroundFaceDetect;",
            "private FaceFeature backgroundFaceFeature;",
            "private final Object backgroundFaceOperationLock = new Object();",
            "backgroundFaceInstance = new BDFaceInstance();",
            "backgroundFaceInstance.creatInstance();",
            "backgroundFaceDetect = new FaceDetect(backgroundFaceInstance);",
            "backgroundFaceFeature = new FaceFeature(backgroundFaceInstance);",
        ]:
            self.assertIn(token, self.sdk)

    def test_background_models_are_part_of_global_ready_gate(self) -> None:
        self.assertIn("new AtomicInteger(6)", self.sdk)
        ready = self.sdk[self.sdk.index("private boolean isModelReady()"):
                         self.sdk.index("private void initLicense")]
        for token in [
            "backgroundFaceInstance != null",
            "backgroundFaceDetect != null",
            "backgroundFaceFeature != null",
        ]:
            self.assertIn(token, ready)
        self.assertIn('handleInitCallback("backgroundDetect"', self.sdk)
        self.assertIn('handleInitCallback("backgroundFeature"', self.sdk)

    def test_background_feature_extraction_is_serialized_behind_bg_gate(self) -> None:
        self.assertIn("public byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance)", self.sdk)
        start = self.sdk.index("public byte[] extractBackgroundFeature(BDFaceImageInstance imageInstance)")
        end = self.sdk.index("public void loadBackgroundConfig", start)
        body = self.sdk[start:end]
        self.assertIn("synchronized (backgroundFaceOperationLock)", body)
        self.assertIn("backgroundFaceDetect.detect", body)
        self.assertIn("backgroundFaceFeature.feature", body)
        self.assertIn("new byte[512]", body)

    def test_background_config_update_uses_same_gate(self) -> None:
        self.assertIn("public void loadBackgroundConfig(BDFaceSDKConfig config)", self.sdk)
        start = self.sdk.index("public void loadBackgroundConfig(BDFaceSDKConfig config)")
        body = self.sdk[start:]
        self.assertIn("synchronized (backgroundFaceOperationLock)", body)
        self.assertIn("backgroundFaceDetect.loadConfig", body)

    def test_file_feature_extraction_routes_to_background_engine_only(self) -> None:
        start = self.manager.index("private byte[] extractFeatureFromFile")
        end = self.manager.index("private String safeEmpId", start)
        body = self.manager[start:end]
        self.assertIn("extractBackgroundFeature(inst)", body)
        self.assertNotIn("getFaceDetectPerson()", body)
        self.assertNotIn("getFacePersonFeature()", body)

    def test_realtime_recognition_still_uses_realtime_getters(self) -> None:
        start = self.manager.index("public RecognizeResult recognizeFromBitmap")
        end = self.manager.index("private RecognizeResult runPreChecks", start)
        body = self.manager[start:end]
        self.assertIn("getFaceDetectPerson()", body)
        self.assertIn("getFacePersonFeature()", body)

    def test_runtime_config_updates_background_detect(self) -> None:
        start = self.manager.index("public void refreshRuntimeConfig()")
        end = self.manager.index("public void rebuildFaceLibrary", start)
        body = self.manager[start:end]
        self.assertIn("loadBackgroundConfig", body)

class FaceFeatureCacheContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_face_features_schema_is_versioned_and_created_on_create_and_upgrade(self) -> None:
        self.assertRegex(self.constants, r"public static final int DB_VERSION = \d+;")
        self.assertGreaterEqual(self.db.count("createFaceFeaturesTable(db);"), 2)
        self.assertIn("if (oldVersion < 10)", self.db)
        create_start = self.db.index("private void createFaceFeaturesTable(SQLiteDatabase db)")
        create_body = self.db[create_start:create_start + 1200]
        for token in [
            '"emp_id TEXT PRIMARY KEY, "',
            '"face_version INTEGER NOT NULL DEFAULT 0, "',
            '"image_sha256 TEXT NOT NULL DEFAULT \'\', "',
            '"feature_schema_version INTEGER NOT NULL, "',
            '"feature BLOB NOT NULL, "',
            '"updated_at INTEGER NOT NULL DEFAULT 0)"',
        ]:
            self.assertIn(token, create_body)

    def test_face_feature_cache_read_requires_matching_metadata_and_512_bytes(self) -> None:
        self.assertIn("public byte[] getValidFaceFeature(", self.db)
        start = self.db.index("public byte[] getValidFaceFeature(")
        end = self.db.index("public boolean upsertFaceFeature(", start)
        body = self.db[start:end]
        self.assertIn("face_version=?", body)
        self.assertIn("COALESCE(image_sha256, '')=?", body)
        self.assertIn("feature_schema_version=?", body)
        self.assertIn("feature.length == 512", body)

    def test_face_feature_cache_upsert_rejects_non_512_features(self) -> None:
        self.assertIn("public boolean upsertFaceFeature(", self.db)
        start = self.db.index("public boolean upsertFaceFeature(")
        end = self.db.index("private void createFaceFeaturesTable", start)
        body = self.db[start:end]
        self.assertIn("feature == null || feature.length != 512", body)
        self.assertIn('values.put("feature", feature)', body)
        self.assertIn("SQLiteDatabase.CONFLICT_REPLACE", body)
        self.assertIn("System.currentTimeMillis()", body)

    def test_hard_employee_cleanup_removes_face_feature_cache(self) -> None:
        remove_start = self.db.index("public void removeEmployee(String id)")
        clear_start = self.db.index("public void clearAllEmployees()", remove_start)
        remove_body = self.db[remove_start:clear_start]
        self.assertIn('delete("face_features", "emp_id=?"', remove_body)

        clear_end = self.db.index("private List<Employee> queryEmployees", clear_start)
        clear_body = self.db[clear_start:clear_end]
        self.assertIn('delete("face_features", null, null)', clear_body)

        local_start = self.db.index("public void clearLocalBusinessData()")
        local_end = self.db.index("private void migrateEmployeesDropAvatarUrl", local_start)
        local_body = self.db[local_start:local_end]
        self.assertIn('db.delete("face_features", null, null)', local_body)

class FaceFeaturePersistenceContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_face_manager_defines_feature_schema_version_one(self) -> None:
        self.assertIn("private static final int FACE_FEATURE_SCHEMA_VERSION = 1;", self.manager)

    def test_successful_registration_or_validation_persists_extracted_feature(self) -> None:
        start = self.manager.index("private RegisterResult registerFaceInternal(")
        end = self.manager.index("private boolean failFullRebuild", start)
        body = self.manager[start:end]
        self.assertIn("persistExtractedFeature(context, empId, prepared.feature);", body)
        persist_pos = body.index("persistExtractedFeature(context, empId, prepared.feature);")
        runtime_apply_pos = body.index("applyStoredFeature(context, empId, prepared.feature)", persist_pos)
        self.assertLess(persist_pos, runtime_apply_pos)

    def test_persist_extracted_feature_uses_current_employee_face_metadata(self) -> None:
        self.assertIn("private void persistExtractedFeature(", self.manager)
        start = self.manager.index("private void persistExtractedFeature(")
        end = self.manager.index("public boolean removeFace", start)
        body = self.manager[start:end]
        for token in [
            "DatabaseHelper db = DatabaseHelper.get(context);",
            "Employee employee = db.getEmployee(empId);",
            "employee.faceVersion",
            "employee.faceImageSha256",
            "FACE_FEATURE_SCHEMA_VERSION",
            "db.upsertFaceFeature(",
        ]:
            self.assertIn(token, body)
        self.assertIn("Face feature cache write failed", body)

class FaceFeatureRebuildCacheContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_rebuild_looks_up_valid_cached_feature_before_image_fallback(self) -> None:
        start = self.manager.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.manager.index("public RegisterResult registerFace", start)
        body = self.manager[start:end]
        self.assertIn("db.getFaceFeaturesByEmployeeIds(rebuildEmployeeIds)", body)
        self.assertIn("loadFeatureForRebuild(context, db, emp, cachedFeatures, stats)", body)
        self.assertNotIn("extractFeatureFromFile(imagePath, emp.id)", body)

    def test_rebuild_helper_uses_metadata_matched_cache_before_decoding_image(self) -> None:
        self.assertIn("private byte[] loadFeatureForRebuild(", self.manager)
        start = self.manager.index("private byte[] loadFeatureForRebuild(")
        end = self.manager.index("private void persistExtractedFeature(", start)
        body = self.manager[start:end]
        cache_pos = body.index("DatabaseHelper.FaceFeatureCacheEntry cached")
        image_pos = body.index("FaceFileManager.getFaceImagePath")
        extract_pos = body.index("extractFeatureFromFile(imagePath, emp.id)")
        self.assertLess(cache_pos, image_pos)
        self.assertLess(image_pos, extract_pos)
        for token in [
            "cached.faceVersion == emp.faceVersion",
            "normalizeFaceSha256(emp.faceImageSha256)",
            "FACE_FEATURE_SCHEMA_VERSION",
            "cached.feature != null && cached.feature.length == 512",
            "stats.cached += 1;",
            "stats.regenerated += 1;",
            "db.upsertFaceFeature(",
        ]:
            self.assertIn(token, body)

    def test_rebuild_keeps_search_commit_two_phase_and_reports_cache_counts(self) -> None:
        start = self.manager.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.manager.index("public RegisterResult registerFace", start)
        body = self.manager[start:end]
        self.assertIn("loadFeatureForRebuild(context, db, emp, cachedFeatures, stats)", body)
        prepare_pos = body.index("loadFeatureForRebuild(context, db, emp, cachedFeatures, stats)")
        lock_pos = body.index("synchronized (faceLibraryLock)")
        self.assertLess(prepare_pos, lock_pos)
        self.assertIn("cached=", body)
        self.assertIn("regenerated=", body)
        self.assertIn("private static final class RebuildFeatureStats", self.manager)

class FaceIncrementalSearchV3ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.registration = read_text("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")

    def test_runtime_registration_replaces_loaded_sdk_id_before_push(self) -> None:
        start = self.manager.index("public RegisterResult applyStoredFeature(")
        end = self.manager.index("private boolean failFullRebuild", start)
        body = self.manager[start:end]
        self.assertIn("Integer loadedIntId = empToIntId.get(empId);", body)
        self.assertIn("boolean wasLoaded = loadedIntId != null;", body)
        delete_pos = body.index("faceSearch.delPersonById(loadedIntId)")
        push_pos = body.index("faceSearch.pushPersonById(intId, feature)")
        self.assertLess(delete_pos, push_pos)
        self.assertIn("if (deleteResult != 0)", body)
        self.assertIn("if (pushResult != 0)", body)
        map_pos = body.index("empToIntId.put(empId, intId)")
        self.assertLess(push_pos, map_pos)

    def test_loaded_face_count_tracks_incremental_add_replace_and_remove(self) -> None:
        start = self.manager.index("public RegisterResult applyStoredFeature(")
        end = self.manager.index("private boolean failFullRebuild", start)
        register_body = self.manager[start:end]
        self.assertIn("if (!wasLoaded) {", register_body)
        self.assertIn("loadedFaceCount += 1;", register_body)

        remove_start = self.manager.index("public boolean removeFace(String empId)")
        remove_end = self.manager.index("public RecognizeResult recognizeFromBitmap", remove_start)
        remove_body = self.manager[remove_start:remove_end]
        self.assertIn("loadedFaceCount = Math.max(0, loadedFaceCount - 1);", remove_body)

    def test_event_mode_persists_then_queues_runtime_apply_without_full_rebuild(self) -> None:
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        self.assertIn("db.commitEmployeeFaceBatch(batchWrites)", body)
        self.assertIn("write.withFeature", body)
        self.assertNotIn("rebuildFinalFaceLibrary(context, app)", body)
        self.assertNotIn("FaceManager.get().registerFace", body)

    def test_event_feature_preparation_does_not_register_runtime_library(self) -> None:
        start = self.sync.index("private FacePreparationOutcome waitForFacePreparation(")
        end = self.sync.index("private FaceRegistrationOutcome waitForFaceRegistration", start)
        body = self.sync[start:end]
        self.assertIn("prepareEmployeesForPersistence", body)
        self.assertNotIn("registerEmployees", body)
        self.assertNotIn("registerFace", body)

    def test_delete_and_disabled_changes_queue_remove_without_direct_runtime_mutation(self) -> None:
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        self.assertIn("DatabaseHelper.FaceBatchWrite.remove(numbers, changeItem.opTime)", body)
        self.assertIn("write.withRemoveTask();", body)
        self.assertNotIn("FaceManager.get().removeFace", body)
        self.assertNotIn("db.updateFaceRegistration", body)

    def test_disabled_employees_are_queued_for_remove_not_feature_preparation(self) -> None:
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        disabled_pos = body.index("if (!isFaceEnabled(merged))")
        remove_pos = body.index("write.withRemoveTask();", disabled_pos)
        prepare_pos = body.index("preparationTargets.put(merged.id, merged)", disabled_pos)
        self.assertLess(remove_pos, prepare_pos)
        self.assertIn("continue;", body[remove_pos:prepare_pos])

    def test_soft_deleted_employee_reactivation_forces_face_reregistration(self) -> None:
        start = self.sync.index("private Employee mergeEmployee(Employee existing, Employee incoming)")
        end = self.sync.index("private String resolveIncomingFaceSha", start)
        body = self.sync[start:end]
        self.assertIn("boolean reactivated = existing.isDeleted != 0;", body)
        self.assertIn("|| reactivated", body)

    def test_preparation_and_explicit_rebuild_paths_keep_full_rebuild(self) -> None:
        explicit_start = self.sync.index("public boolean rebuildLocalFaceLibrary(Context context)")
        explicit_end = self.sync.index("private void runHeartbeatCycle", explicit_start)
        explicit_body = self.sync[explicit_start:explicit_end]
        self.assertIn("rebuildFinalFaceLibrary(appContext, app)", explicit_body)

        prep_start = self.sync.index("FaceRegistrationOutcome registrationOutcome = waitForFaceRegistration(context);",
                                     self.sync.index("private EmployeeSyncProcessingResult syncEmployeesInternal"))
        prep_end = self.sync.index("private Employee mergeEmployee", prep_start)
        prep_body = self.sync[prep_start:prep_end]
        self.assertIn("rebuildFinalFaceLibrary(context, app)", prep_body)

    def test_runtime_registration_failures_remove_stale_runtime_face(self) -> None:
        register_start = self.registration.index("private RegistrationResult registerSingle(")
        register_end = self.registration.index("private RegistrationResult failRegistration(", register_start)
        register_body = self.registration[register_start:register_end]
        self.assertGreaterEqual(register_body.count("failRegistration(ctx, emp,"), 3)
        self.assertIn("private RegistrationResult failRegistration(", self.registration)
        start = self.registration.index("private RegistrationResult failRegistration(")
        end = self.registration.index("private int countSucceeded", start)
        body = self.registration[start:end]
        self.assertIn("if (addToRuntimeLibrary)", body)
        self.assertIn("FaceManager.get().removeFace(emp.id);", body)
        self.assertIn("DatabaseHelper.get(ctx).updateFaceRegistration(emp.id, null, false);", body)

class FaceRebuildConsistencyV4ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def _rebuild_body(self) -> str:
        start = self.manager.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.manager.index("public RegisterResult registerFace", start)
        return self.manager[start:end]

    def test_runtime_library_has_explicit_fail_closed_state(self) -> None:
        for token in [
            "public enum FaceLibraryState",
            "NOT_READY",
            "REBUILDING",
            "READY",
            "REBUILD_FAILED",
            "private volatile FaceLibraryState faceLibraryState = FaceLibraryState.NOT_READY;",
            "public boolean isFaceLibraryReady()",
            "ERROR_FACE_LIBRARY_NOT_READY",
        ]:
            self.assertIn(token, self.manager)

    def test_rebuild_preparation_failure_cannot_commit_partial_entry_set(self) -> None:
        body = self._rebuild_body()
        self.assertIn("int expectedEntries = rebuildEmployees.size();", body)
        self.assertIn("boolean preparationFailed = false;", body)
        self.assertIn("rebuildEmployees.add(emp);", body)
        self.assertIn("preparationFailed = true;", body)
        self.assertIn("entries.size() != expectedEntries", body)
        fail_pos = body.index("entries.size() != expectedEntries")
        lock_pos = body.index("synchronized (faceLibraryLock)")
        self.assertLess(fail_pos, lock_pos)

    def test_rebuild_checks_native_clear_and_every_push_result(self) -> None:
        body = self._rebuild_body()
        self.assertIn("int clearResult = faceSearch.featureClear();", body)
        self.assertIn("if (clearResult != 0)", body)
        self.assertIn("int pushResult = faceSearch.pushPersonById(entry.sdkId, entry.feature);", body)
        self.assertIn("if (pushResult != 0)", body)

    def test_rebuild_verifies_native_size_before_publishing_java_state(self) -> None:
        body = self._rebuild_body()
        self.assertIn("int nativeSize = faceSearch.getSize();", body)
        self.assertIn("if (nativeSize != entries.size())", body)
        native_pos = body.index("int nativeSize = faceSearch.getSize();")
        publish_map_pos = body.index("empToIntId.putAll(newEmpToIntId);")
        publish_count_pos = body.index("loadedFaceCount = entries.size();")
        ready_pos = body.index("faceLibraryState = FaceLibraryState.READY;")
        self.assertLess(native_pos, publish_map_pos)
        self.assertLess(native_pos, publish_count_pos)
        self.assertLess(native_pos, ready_pos)

    def test_rebuild_builds_new_mappings_off_to_the_side_until_commit_succeeds(self) -> None:
        body = self._rebuild_body()
        self.assertIn("Map<String, Integer> newEmpToIntId = new HashMap<>();", body)
        self.assertIn("Map<Integer, String> newIntToEmpId = new HashMap<>();", body)
        self.assertIn("newEmpToIntId.put(entry.empId, entry.sdkId);", body)
        self.assertIn("newIntToEmpId.put(entry.sdkId, entry.empId);", body)
        self.assertIn("empToIntId.clear();", body)
        self.assertIn("empToIntId.putAll(newEmpToIntId);", body)
        self.assertIn("intToEmpId.clear();", body)
        self.assertIn("intToEmpId.putAll(newIntToEmpId);", body)

    def test_full_rebuild_failure_cleanup_is_fail_closed(self) -> None:
        self.assertIn("private boolean failFullRebuildLocked(", self.manager)
        start = self.manager.index("private boolean failFullRebuildLocked(")
        end = self.manager.index("private byte[] loadFeatureForRebuild", start)
        body = self.manager[start:end]
        for token in [
            "faceLibraryState = FaceLibraryState.REBUILD_FAILED;",
            "empToIntId.clear();",
            "intToEmpId.clear();",
            "loadedFaceCount = 0;",
            "faceSearch.featureClear()",
            "return false;",
        ]:
            self.assertIn(token, body)

    def test_search_checks_ready_inside_face_library_lock_before_native_search(self) -> None:
        start = self.manager.index("private RecognizeResult doSearch(byte[] feature, FaceInfo faceInfo)")
        end = self.manager.index("private RectF buildFaceBounds", start)
        body = self.manager[start:end]
        lock_pos = body.index("synchronized (faceLibraryLock)")
        state_pos = body.index("observedState != FaceLibraryState.READY", lock_pos)
        search_pos = body.index("faceSearch.search(", lock_pos)
        self.assertLess(lock_pos, state_pos)
        self.assertLess(state_pos, search_pos)
        self.assertIn("ERROR_FACE_LIBRARY_NOT_READY", body)


class FaceInitializationStateV5ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def _init_body(self) -> str:
        start = self.manager.index("public void init(Context context")
        end = self.manager.index("public void refreshRuntimeConfig()", start)
        return self.manager[start:end]

    def test_face_manager_singleton_uses_holder_idiom(self) -> None:
        self.assertIn("private static final class Holder", self.manager)
        self.assertIn("private static final FaceManager INSTANCE = new FaceManager();", self.manager)
        self.assertIn("return Holder.INSTANCE;", self.manager)
        self.assertNotIn("private static FaceManager instance;", self.manager)

    def test_initialization_has_explicit_volatile_state(self) -> None:
        for token in [
            "public enum InitState",
            "UNINITIALIZED",
            "INITIALIZING",
            "READY",
            "FAILED",
            "private volatile InitState initState = InitState.UNINITIALIZED;",
            "private final Object initLock = new Object();",
        ]:
            self.assertIn(token, self.manager)

    def test_concurrent_init_calls_coalesce_onto_one_inflight_attempt(self) -> None:
        body = self._init_body()
        self.assertIn("synchronized (initLock)", body)
        self.assertIn("pendingInitCallbacks.add(callback);", body)
        self.assertIn("if (initState == InitState.INITIALIZING)", body)
        self.assertIn("initState = InitState.INITIALIZING;", body)
        self.assertEqual(1, body.count("FaceSDKManager.getInstance().initModel("))

    def test_ready_init_returns_success_without_restarting_sdk(self) -> None:
        body = self._init_body()
        ready_pos = body.index("if (initState == InitState.READY)")
        sdk_pos = body.index("FaceSDKManager.getInstance().initModel(")
        self.assertLess(ready_pos, sdk_pos)
        self.assertIn("notifyInitSuccess(callback);", body)

    def test_success_and_failure_publish_state_then_notify_all_waiters(self) -> None:
        for method_name, expected_state, notifier in [
            ("private void completeInitSuccess()", "InitState.READY", "notifyInitSuccess(callback);"),
            ("private void completeInitFailure(int code, String message)", "InitState.FAILED", "notifyInitError(callback, code, message);"),
        ]:
            start = self.manager.index(method_name)
            end = self.manager.index("\n    }", start) + len("\n    }")
            body = self.manager[start:end]
            self.assertIn(f"initState = {expected_state};", body)
            self.assertIn("drainPendingInitCallbacksLocked()", body)
            self.assertIn(notifier, body)

    def test_failed_state_is_retryable_on_next_init_call(self) -> None:
        body = self._init_body()
        self.assertNotIn("if (initState == InitState.FAILED) {\n                return;", body)
        failed_pos = self.manager.index("initState = InitState.FAILED;")
        self.assertGreaterEqual(failed_pos, 0)
        self.assertIn("initState = InitState.INITIALIZING;", body)

    def test_initialized_boolean_is_replaced_by_state_read(self) -> None:
        self.assertNotIn("private boolean initialized", self.manager)
        self.assertNotRegex(self.manager, r"\binitialized\s*=\s*(true|false)")
        start = self.manager.index("public boolean isInitialized()")
        end = self.manager.index("\n    }", start) + len("\n    }")
        body = self.manager[start:end]
        self.assertIn("return initState == InitState.READY;", body)
        self.assertNotIn("if (!initialized)", self.manager)
        self.assertIn("public InitState getInitState()", self.manager)

    def test_synchronous_sdk_init_exception_transitions_to_failed(self) -> None:
        body = self._init_body()
        self.assertIn("try {", body)
        self.assertIn("FaceSDKManager.getInstance().initModel(", body)
        self.assertIn("catch (RuntimeException e)", body)
        self.assertIn("completeInitFailure(-1, \"SDK init exception: \" + e.getMessage());", body)


class SyncTriggerLifecycleV6ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.sync_service = read_text("app/src/main/java/com/punch/app/service/SyncService.java")
        self.heartbeat = read_text("app/src/main/java/com/punch/app/service/HeartbeatManager.java")

    def _trigger_body(self) -> str:
        start = self.sync_service.index(
            "public static void triggerSync(Context context, SyncTrigger trigger)"
        )
        end = self.sync_service.index("private static SyncTrigger parseTrigger", start)
        return self.sync_service[start:end]

    def test_trigger_sync_directly_dispatches_to_heartbeat_manager(self) -> None:
        body = self._trigger_body()
        self.assertIn("HeartbeatManager.get(appContext).triggerNow(safeTrigger);", body)

    def test_trigger_sync_no_longer_starts_android_service(self) -> None:
        body = self._trigger_body()
        self.assertNotIn("startService", body)
        self.assertNotIn("new Intent", body)
        self.assertNotIn("ACTION_SYNC_NOW", body)
        self.assertNotIn("EXTRA_SYNC_TRIGGER", body)

    def test_trigger_sync_preserves_token_gate_and_trigger_default(self) -> None:
        body = self._trigger_body()
        self.assertIn("if (!SessionManager.get().isTokenValid())", body)
        self.assertIn("trigger != null ? trigger : SyncTrigger.AFTER_PUNCH", body)
        self.assertIn("Context appContext = context.getApplicationContext();", body)

    def test_heartbeat_trigger_now_starts_scheduler_and_enqueues_same_trigger(self) -> None:
        start = self.heartbeat.index("public void triggerNow(SyncTrigger trigger)")
        end = self.heartbeat.index("public synchronized void stop()", start)
        body = self.heartbeat[start:end]
        self.assertIn("start();", body)
        self.assertIn("SyncCoordinator.get().enqueueHeartbeatCycle(appContext, safeTrigger);", body)

    def test_legacy_service_entry_point_still_delegates_if_started_explicitly(self) -> None:
        self.assertIn("public int onStartCommand(Intent intent, int flags, int startId)", self.sync_service)
        self.assertIn("HeartbeatManager.get(getApplicationContext()).triggerNow(", self.sync_service)
        self.assertIn("return START_NOT_STICKY;", self.sync_service)


class PunchPreparationStatusRaceV7ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.app = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.login = read_text("app/src/main/java/com/punch/app/activity/LoginActivity.java")

    def _post_login_body(self) -> str:
        start = self.login.index("private void postLogin()")
        end = self.login.index("private boolean shouldBootstrapDeviceConfig()", start)
        return self.login[start:end]

    def _restart_body(self) -> str:
        self.assertIn("public void restartPunchRecognitionData()", self.app)
        start = self.app.index("public void restartPunchRecognitionData()")
        end = self.app.index("public boolean isPunchRecognitionReady()", start)
        return self.app[start:end]

    def test_login_uses_single_restart_api_for_punch_preparation(self) -> None:
        body = self._post_login_body()
        self.assertIn("app.restartPunchRecognitionData();", body)
        self.assertNotIn("app.resetPunchRecognitionState();", body)
        self.assertNotIn("app.setCurrentPunchStatus(", body)
        self.assertNotIn("app.preparePunchRecognitionData();", body)

    def test_restart_publishes_initializing_state_and_always_queues_preparation(self) -> None:
        body = self._restart_body()
        self.assertIn('beginPunchDataPreparation("正在初始化打卡环境...");', body)
        self.assertIn("appExecutor.execute(this::runPunchPreparation);", body)
        self.assertNotIn("if (punchDataPreparing || punchDataReady)", body)

    def test_restart_is_serialized_behind_any_existing_preparation_task(self) -> None:
        body = self._restart_body()
        self.assertIn("appExecutor.execute(this::runPunchPreparation);", body)
        self.assertNotIn("new Thread", body)
        self.assertNotIn("Executors.new", body)

    def test_normal_prepare_remains_idempotent_for_frame_retry_paths(self) -> None:
        start = self.app.index("public void preparePunchRecognitionData()")
        end_marker = "public void restartPunchRecognitionData()"
        end = self.app.index(end_marker, start) if end_marker in self.app[start:] else self.app.index("public boolean isPunchRecognitionReady()", start)
        body = self.app[start:end]
        self.assertIn("if (punchDataPreparing || punchDataReady)", body)
        self.assertIn("appExecutor.execute(this::runPunchPreparation);", body)

class FaceRealtimeSearchV8ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manager = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        start = self.manager.index("private RecognizeResult doSearch(byte[] feature, FaceInfo faceInfo)")
        end = self.manager.index("private RectF buildFaceBounds", start)
        self.body = self.manager[start:end]

    @staticmethod
    def _balanced_block(text: str, brace_pos: int) -> str:
        depth = 0
        for i in range(brace_pos, len(text)):
            ch = text[i]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[brace_pos:i + 1]
        raise AssertionError("unterminated block")

    def _search_lock_block(self) -> str:
        lock_pos = self.body.index("synchronized (faceLibraryLock)")
        brace_pos = self.body.index("{", lock_pos)
        return self._balanced_block(self.body, brace_pos)

    def test_non_ready_fast_fails_before_waiting_for_face_library_lock(self) -> None:
        first_state_pos = self.body.index("observedState != FaceLibraryState.READY")
        lock_pos = self.body.index("synchronized (faceLibraryLock)")
        self.assertLess(first_state_pos, lock_pos)
        prefix = self.body[:lock_pos]
        self.assertIn("ERROR_FACE_LIBRARY_NOT_READY", prefix)

    def test_search_double_checks_ready_inside_lock_before_native_search(self) -> None:
        self.assertGreaterEqual(
            self.body.count("observedState != FaceLibraryState.READY"),
            2,
        )
        lock_body = self._search_lock_block()
        state_pos = lock_body.index("observedState != FaceLibraryState.READY")
        search_pos = lock_body.index("faceSearch.search(")
        self.assertLess(state_pos, search_pos)

    def test_search_lock_only_snapshots_native_result_and_runtime_mapping(self) -> None:
        lock_body = self._search_lock_block()
        self.assertIn("faceSearch.search(", lock_body)
        self.assertIn("intToEmpId.get(", lock_body)
        for token in [
            "Face search miss",
            "Face search below threshold",
            "Reject ambiguous face match",
            "Face search matched",
            "SAFE_MATCH_SCORE_GAP",
            "buildFaceBounds(faceInfo)",
        ]:
            self.assertNotIn(token, lock_body)

    def test_threshold_ambiguity_logging_and_result_construction_remain_outside_lock(self) -> None:
        lock_pos = self.body.index("synchronized (faceLibraryLock)")
        brace_pos = self.body.index("{", lock_pos)
        lock_body = self._balanced_block(self.body, brace_pos)
        after_lock = self.body[self.body.index(lock_body, lock_pos) + len(lock_body):]
        for token in [
            "bestScore < effectiveThreshold * 100",
            "scoreGap < SAFE_MATCH_SCORE_GAP",
            "ERROR_MATCH_AMBIGUOUS",
            "ERROR_FACE_ID_MAPPING_MISSING",
            "Face search matched",
            "buildFaceBounds(faceInfo)",
        ]:
            self.assertIn(token, after_lock)


class FaceDeliveryV9DatabaseContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")

    def test_db_version_and_forward_migration_create_face_apply_tasks(self) -> None:
        self.assertIn("createFaceApplyTasksTable(db);", self.db)
        self.assertRegex(self.db, r"if \(oldVersion < 11\)\s*\{\s*createFaceApplyTasksTable\(db\);")
        for token in [
            "CREATE TABLE IF NOT EXISTS face_apply_tasks",
            "id INTEGER PRIMARY KEY AUTOINCREMENT",
            "emp_id TEXT NOT NULL UNIQUE",
            "operation TEXT NOT NULL",
            "face_version INTEGER NOT NULL DEFAULT 0",
            "retry_count INTEGER NOT NULL DEFAULT 0",
            "last_error TEXT NOT NULL DEFAULT ''",
        ]:
            self.assertIn(token, self.db)

    def test_batch_commit_is_one_sqlite_transaction_for_employee_feature_and_task(self) -> None:
        self.assertIn("public boolean commitEmployeeFaceBatch(List<FaceBatchWrite> writes)", self.db)
        start = self.db.index("public boolean commitEmployeeFaceBatch(List<FaceBatchWrite> writes)")
        end = self.db.index("public FaceApplyTask getNextFaceApplyTask()", start)
        body = self.db[start:end]
        self.assertIn("db.beginTransaction();", body)
        self.assertIn("writeEmployeeForBatch(db, write.employee);", body)
        self.assertIn("writeFaceFeatureForBatch(db, write);", body)
        self.assertIn("writeFaceApplyTaskForBatch(db, write);", body)
        self.assertIn("db.setTransactionSuccessful();", body)
        self.assertIn("db.endTransaction();", body)

    def test_task_completion_and_failure_are_guarded_by_task_id(self) -> None:
        self.assertIn("public boolean deleteFaceApplyTask(long taskId)", self.db)
        self.assertIn('db.delete("face_apply_tasks", "id=?"', self.db)
        self.assertIn("public void markFaceApplyTaskRetry(long taskId, String error, long nextRetryAt)", self.db)
        self.assertIn("public void markFaceApplyTaskPermanentFailed(long taskId, String error)", self.db)
        self.assertIn('"id=?"', self.db)


class FaceDeliveryV9RuntimeMutationContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def _method(self, signature: str, next_signature: str) -> str:
        start = self.face.index(signature)
        end = self.face.index(next_signature, start)
        return self.face[start:end]

    def test_apply_stored_feature_does_not_decode_extract_or_persist_feature(self) -> None:
        body = self._method(
            "public RegisterResult applyStoredFeature(Context context, String empId, byte[] feature)",
            "private boolean failFullRebuild",
        )
        self.assertIn("feature == null || feature.length != 512", body)
        self.assertNotIn("extractFeatureFromFile", body)
        self.assertNotIn("persistExtractedFeature", body)
        self.assertNotIn("BitmapFactory", body)
        self.assertIn("faceSearch.pushPersonById", body)

    def test_replace_checks_native_delete_before_push_and_keeps_mapping_on_delete_failure(self) -> None:
        body = self._method(
            "public RegisterResult applyStoredFeature(Context context, String empId, byte[] feature)",
            "private boolean failFullRebuild",
        )
        delete_pos = body.index("faceSearch.delPersonById")
        push_pos = body.index("faceSearch.pushPersonById")
        self.assertLess(delete_pos, push_pos)
        self.assertIn("if (deleteResult != 0)", body)
        delete_fail = body[body.index("if (deleteResult != 0)"):push_pos]
        self.assertNotIn("empToIntId.remove", delete_fail)
        self.assertNotIn("intToEmpId.remove", delete_fail)

    def test_remove_mutates_java_mapping_only_after_native_delete_success(self) -> None:
        body = self._method("public boolean removeFace(String empId)", "public RecognizeResult recognizeFromBitmap")
        delete_pos = body.index("faceSearch.delPersonById")
        map_remove_pos = body.index("empToIntId.remove")
        self.assertLess(delete_pos, map_remove_pos)
        self.assertIn("if (deleteResult != 0)", body)
        self.assertIn("return false;", body)
        self.assertIn("return true;", body)


class FaceDeliveryV9WorkerContractTest(unittest.TestCase):
    def setUp(self) -> None:
        path = ROOT / "app/src/main/java/com/punch/app/face/FaceApplyWorker.java"
        self.worker = path.read_text(encoding="utf-8") if path.is_file() else ""

    def test_worker_is_single_threaded_and_coalesces_triggers(self) -> None:
        self.assertIn("Executors.newSingleThreadScheduledExecutor()", self.worker)
        self.assertIn("AtomicBoolean queued", self.worker)
        self.assertIn("public void trigger(Context context)", self.worker)
        self.assertIn("queued.compareAndSet(false, true)", self.worker)

    def test_worker_consumes_persisted_tasks_and_dispatches_upsert_remove(self) -> None:
        self.assertIn("db.getNextReadyFaceApplyTask(afterTaskId, nowMs)", self.worker)
        self.assertIn('"UPSERT".equals(task.operation)', self.worker)
        self.assertIn('"REMOVE".equals(task.operation)', self.worker)
        self.assertIn("applyStoredFeature(", self.worker)
        self.assertIn("faceManager.removeFace", self.worker)

    def test_worker_completes_exact_task_and_does_not_head_of_line_block_after_failure(self) -> None:
        self.assertIn("db.completeFaceApplyTask(task.id, task.empId", self.worker)
        self.assertIn("db.markFaceApplyTaskRetry(task.id, outcome.error, nextRetryAt)", self.worker)
        self.assertIn("db.markFaceApplyTaskPermanentFailed(task.id, outcome.error)", self.worker)
        self.assertIn("afterTaskId = task.id;", self.worker)
        fail_pos = self.worker.index("db.markFaceApplyTaskRetry(task.id, outcome.error, nextRetryAt);")
        tail = self.worker[fail_pos:self.worker.index("private ApplyOutcome applyOne", fail_pos)]
        self.assertNotIn("break;", tail)


class FaceDeliveryV9BatchAckContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.api = read_text("app/src/main/java/com/punch/app/network/ApiService.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.reg = read_text("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")

    def test_employee_sync_request_fields_are_unchanged_and_page_size_is_200(self) -> None:
        self.assertIn("private static final int EMPLOYEE_SYNC_PAGE_SIZE = 200;", self.api)
        start = self.api.index("public static ApiResult<EmployeeSyncData> syncEmployees(int page)")
        end = self.api.index("public static ApiResult<Void> reportEventResult", start)
        body = self.api[start:end]
        for token in ['body.put("device_id"', 'body.put("page", page);', 'body.put("page_size", EMPLOYEE_SYNC_PAGE_SIZE);', 'body.put("op_status", 1);']:
            self.assertIn(token, body)
        for forbidden in ["batch_cursor", "batch_size", "event_cursor"]:
            self.assertNotIn(forbidden, body)

    def test_person_changed_uses_dedicated_persistence_loop_and_single_final_ack(self) -> None:
        self.assertIn("syncEmployeesEventInBatches(appContext, event)", self.sync)
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("ApiService.syncEmployees(page)", body)
        self.assertEqual(1, body.count("ApiService.reportEventResult("))
        self.assertIn("event.cursor", body)
        self.assertIn("eventEmployeeResults.addAll(batch.employeeResults);", body)
        self.assertIn("FaceApplyWorker.get().trigger(context);", body)
        self.assertIn("page += 1;", body)
        outer = self.sync[self.sync.index("private void applyHeartbeatEvents"):self.sync.index("private boolean syncEmployeesEventInBatches", self.sync.index("private void applyHeartbeatEvents"))]
        self.assertIn('if ("person_changed".equals(safeString(event.eventType)))', outer)
        self.assertIn("continue;", outer)

    def test_event_batch_coalesces_duplicate_employee_changes_to_latest_desired_state(self) -> None:
        self.assertIn("coalesceLatestEmployeeChanges(changeItems)", self.sync)
        self.assertIn("private List<EmployeeSyncData.ChangeItem> coalesceLatestEmployeeChanges", self.sync)
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        self.assertIn("for (EmployeeSyncData.ChangeItem changeItem : effectiveChanges)", body)

    def test_background_person_changed_does_not_disable_ready_punch_environment(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("app.beginEmployeeSyncProgress(event.cursor)", body)
        self.assertNotIn("beginPunchDataPreparation", body)
        self.assertNotIn("markPunchRecognitionFailed", body)
        self.assertNotIn("updatePunchDataPreparationStatus", body)

    def test_event_batch_persists_before_ack_and_does_not_mutate_facesearch(self) -> None:
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        self.assertIn("db.commitEmployeeFaceBatch(batchWrites)", body)
        self.assertNotIn("FaceManager.get().removeFace", body)
        self.assertNotIn("FaceManager.get().registerFace", body)
        self.assertNotIn("rebuildFaceLibrary", body)

    def test_face_preparation_returns_feature_without_runtime_registration(self) -> None:
        self.assertIn("public void prepareEmployeesForPersistence", self.reg)
        start = self.reg.index("public void prepareEmployeesForPersistence")
        end = self.reg.index("public void refreshEmployee", start)
        body = self.reg[start:end]
        self.assertIn("prepareEmployeesInternal", body)
        self.assertNotIn("registerFace", body)


class FaceApplyRetryV10ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        path = ROOT / "app/src/main/java/com/punch/app/face/FaceApplyWorker.java"
        self.worker = path.read_text(encoding="utf-8") if path.is_file() else ""

    def test_db_v12_adds_retry_state_and_next_retry_at_with_forward_migration(self) -> None:
        self.assertIn("public static final int DB_VERSION = 12;", self.constants)
        for token in [
            "state TEXT NOT NULL DEFAULT 'PENDING'",
            "next_retry_at INTEGER NOT NULL DEFAULT 0",
        ]:
            self.assertIn(token, self.db)
        self.assertRegex(self.db, r"if \(oldVersion < 12\)\s*\{")
        self.assertIn('addColumnIfMissing(db, "face_apply_tasks", "state", "TEXT NOT NULL DEFAULT \'PENDING\'")', self.db)
        self.assertIn('addColumnIfMissing(db, "face_apply_tasks", "next_retry_at", "INTEGER NOT NULL DEFAULT 0")', self.db)

    def test_new_desired_state_resets_retry_metadata_to_pending_now(self) -> None:
        start = self.db.index("private void writeFaceApplyTaskForBatch")
        end = self.db.index("public boolean hasFaceApplyTask", start)
        body = self.db[start:end]
        self.assertIn('values.put("state", "PENDING")', body)
        self.assertIn('values.put("retry_count", 0)', body)
        self.assertIn('values.put("next_retry_at", 0)', body)
        self.assertIn('values.put("last_error", "")', body)

    def test_ready_task_query_filters_pending_and_due_time(self) -> None:
        self.assertIn("public FaceApplyTask getNextReadyFaceApplyTask(long afterId, long nowMs)", self.db)
        start = self.db.index("public FaceApplyTask getNextReadyFaceApplyTask(long afterId, long nowMs)")
        end = self.db.index("public long getNextFaceApplyRetryAt()", start)
        body = self.db[start:end]
        self.assertIn("state='PENDING'", body)
        self.assertIn("next_retry_at<=?", body)
        self.assertIn("ORDER BY id ASC LIMIT 1", body)
        self.assertIn("return readFaceApplyTask(c);", body)
        helper_start = self.db.index("private FaceApplyTask readFaceApplyTask(Cursor c)")
        helper_end = self.db.index("public boolean deleteFaceApplyTask", helper_start)
        helper = self.db[helper_start:helper_end]
        self.assertIn("task.nextRetryAt", helper)
        self.assertIn("task.state", helper)

    def test_retry_and_permanent_failure_have_distinct_database_updates(self) -> None:
        self.assertIn("public void markFaceApplyTaskRetry(long taskId, String error, long nextRetryAt)", self.db)
        self.assertIn("retry_count=retry_count+1", self.db)
        self.assertIn("next_retry_at=?", self.db)
        self.assertIn("public void markFaceApplyTaskPermanentFailed(long taskId, String error)", self.db)
        permanent = self.db[self.db.index("public void markFaceApplyTaskPermanentFailed"):self.db.index("public boolean completeFaceApplyTask", self.db.index("public void markFaceApplyTaskPermanentFailed"))]
        self.assertIn('state=\'FAILED\'', permanent)
        self.assertNotIn("retry_count=retry_count+1", permanent)

    def test_worker_uses_single_thread_scheduled_executor_and_capped_backoff(self) -> None:
        self.assertIn("Executors.newSingleThreadScheduledExecutor()", self.worker)
        for token in [
            "1_000L",
            "5_000L",
            "30_000L",
            "60_000L",
            "300_000L",
        ]:
            self.assertIn(token, self.worker)
        self.assertIn("private long retryDelayMs(int retryCount)", self.worker)
        self.assertIn("Math.min", self.worker)

    def test_worker_only_reads_due_tasks_and_schedules_earliest_future_retry(self) -> None:
        self.assertIn("db.getNextReadyFaceApplyTask(afterTaskId, nowMs)", self.worker)
        self.assertIn("db.getNextFaceApplyRetryAt()", self.worker)
        self.assertIn("scheduleRetry(context, nextRetryAt)", self.worker)
        self.assertIn("TimeUnit.MILLISECONDS", self.worker)

    def test_worker_classifies_retryable_runtime_failures_and_permanent_data_errors(self) -> None:
        self.assertIn("ApplyOutcome.retry", self.worker)
        self.assertIn("ApplyOutcome.permanent", self.worker)
        for token in [
            "Face runtime is not ready",
            "FaceSearch remove failed",
            "Persisted face feature is missing",
            "Face task version is stale",
            "Unknown face apply operation",
        ]:
            self.assertIn(token, self.worker)
        self.assertIn("db.markFaceApplyTaskRetry", self.worker)
        self.assertIn("db.markFaceApplyTaskPermanentFailed", self.worker)

    def test_worker_does_not_spin_retry_failed_task_in_same_drain(self) -> None:
        self.assertIn("private ApplyOutcome applyOne", self.worker)
        start = self.worker.index("private void drain(Context context)")
        end = self.worker.index("private ApplyOutcome applyOne", start)
        body = self.worker[start:end]
        self.assertIn("afterTaskId = task.id;", body)
        self.assertIn("nextRetryAt", body)
        self.assertNotIn("while (System.currentTimeMillis() <", body)
        self.assertNotIn("Thread.sleep", body)

    def test_full_rebuild_only_treats_pending_upsert_as_required_runtime_state(self) -> None:
        face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.assertIn("public boolean hasPendingFaceApplyTask(String empId, String operation)", self.db)
        db_start = self.db.index("public boolean hasPendingFaceApplyTask(String empId, String operation)")
        db_end = self.db.index("public FaceApplyTask getNextFaceApplyTask()", db_start)
        db_body = self.db[db_start:db_end]
        self.assertIn("state='PENDING'", db_body)
        rebuild_start = face.index("public boolean rebuildFaceLibrarySync(Context context)")
        rebuild_end = face.index("public RegisterResult registerFace", rebuild_start)
        rebuild = face[rebuild_start:rebuild_end]
        self.assertIn("db.getPendingFaceApplyEmployeeIds(", rebuild)
        self.assertIn("DatabaseHelper.FaceBatchWrite.OP_UPSERT", rebuild)
        self.assertIn("pendingRuntimeUpsertEmployeeIds.contains(emp.id)", rebuild)
        self.assertNotIn("db.hasFaceApplyTask(", rebuild)



class FaceApplyBackpressureV11ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")

    def test_pending_backlog_count_only_counts_pending_tasks(self) -> None:
        self.assertIn("public int getPendingFaceApplyTaskCount()", self.db)
        start = self.db.index("public int getPendingFaceApplyTaskCount()")
        end = self.db.index("public FaceApplyTask getNextFaceApplyTask()", start)
        body = self.db[start:end]
        self.assertIn("SELECT COUNT(*) FROM face_apply_tasks WHERE state='PENDING'", body)
        self.assertNotIn("state='FAILED'", body)

    def test_sync_uses_500_high_and_200_low_watermarks(self) -> None:
        self.assertIn("FACE_APPLY_BACKLOG_HIGH_WATERMARK = 500", self.sync)
        self.assertIn("FACE_APPLY_BACKLOG_LOW_WATERMARK = 200", self.sync)
        self.assertIn("AtomicBoolean faceApplyBackpressureActive", self.sync)

    def test_backpressure_gate_uses_hysteresis_and_triggers_worker(self) -> None:
        self.assertIn("private boolean shouldPauseEmployeeBatchFetch(Context context)", self.sync)
        start = self.sync.index("private boolean shouldPauseEmployeeBatchFetch(Context context)")
        end = self.sync.index("private boolean syncEmployeesEventInBatches", start)
        body = self.sync[start:end]
        self.assertIn("db.getPendingFaceApplyTaskCount()", body)
        self.assertIn("pendingCount >= FACE_APPLY_BACKLOG_HIGH_WATERMARK", body)
        self.assertIn("pendingCount > FACE_APPLY_BACKLOG_LOW_WATERMARK", body)
        self.assertIn("faceApplyBackpressureActive.set(true)", body)
        self.assertIn("faceApplyBackpressureActive.set(false)", body)
        self.assertIn("FaceApplyWorker.get().trigger(context)", body)

    def test_single_ack_event_loop_does_not_use_backpressure_as_a_mid_event_gate(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("ApiService.syncEmployees(page)", body)
        self.assertIn("FaceApplyWorker.get().trigger(context);", body)
        self.assertNotIn("shouldPauseEmployeeBatchFetch(context)", body)

    def test_single_ack_event_loop_never_waits_or_yields_on_apply_backlog(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertNotIn("Thread.sleep", body)
        self.assertNotIn("while (db.getPendingFaceApplyTaskCount()", body)
        self.assertNotIn("if (shouldPauseEmployeeBatchFetch(context))", body)
        self.assertEqual(1, body.count("ApiService.reportEventResult("))


class FaceDeliveryV9RecoveryContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.app = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_full_rebuild_includes_durable_pending_upsert_even_before_registered_flag_commit(self) -> None:
        self.assertIn("public boolean hasPendingFaceApplyTask(String empId, String operation)", self.db)
        start = self.face.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.face.index("public RegisterResult registerFace", start)
        body = self.face[start:end]
        self.assertIn("DatabaseHelper.FaceBatchWrite.OP_UPSERT", body)
        self.assertIn("db.getPendingFaceApplyEmployeeIds(", body)
        self.assertIn("DatabaseHelper.FaceBatchWrite.OP_UPSERT", body)
        self.assertIn("boolean registeredForRuntime = emp.faceRegistered == 1", body)
        self.assertIn("boolean pendingRuntimeUpsert = pendingRuntimeUpsertEmployeeIds.contains(emp.id)", body)
        self.assertIn("registeredForRuntime || pendingRuntimeUpsert", body)

    def test_heartbeat_retries_durable_face_apply_when_runtime_is_ready(self) -> None:
        sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        start = sync.index("private void runHeartbeatCycle(Context appContext, SyncTrigger trigger)")
        end = sync.index("private void applyHeartbeatEvents", start)
        body = sync[start:end]
        self.assertIn("FaceManager.get().isFaceLibraryReady()", body)
        self.assertIn("FaceApplyWorker.get().trigger(appContext);", body)

    def test_successful_preparation_triggers_pending_face_apply_after_runtime_ready(self) -> None:
        start = self.app.index("private void runPunchPreparation()")
        end = self.app.index("private boolean waitForFaceSdkReady()", start)
        body = self.app[start:end]
        self.assertIn("FaceApplyWorker.get().trigger(this);", body)
        trigger_pos = body.index("FaceApplyWorker.get().trigger(this);")
        rebuild_pos = body.index("rebuildLocalFaceLibrary(this)") if "rebuildLocalFaceLibrary(this)" in body else -1
        if rebuild_pos >= 0:
            self.assertGreater(trigger_pos, rebuild_pos)

class FaceStartupReconcileV12ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.app = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        path = ROOT / "app/src/main/java/com/punch/app/face/FaceLibraryReconciler.java"
        self.reconciler = path.read_text(encoding="utf-8") if path.is_file() else ""

    def test_reconciler_component_exists_and_uses_runtime_and_database_state(self) -> None:
        self.assertTrue(self.reconciler)
        self.assertIn("class FaceLibraryReconciler", self.reconciler)
        self.assertIn("FaceManager.RuntimeSnapshot", self.reconciler)
        self.assertIn("getActiveRegisteredFaceEmployeeIds()", self.reconciler)
        self.assertIn("getPendingFaceApplyTaskEmployeeIds", self.reconciler)

    def test_database_exposes_registered_and_pending_employee_sets(self) -> None:
        self.assertIn("public Set<String> getActiveRegisteredFaceEmployeeIds()", self.db)
        self.assertIn("public Set<String> getPendingFaceApplyTaskEmployeeIds(String operation)", self.db)
        start = self.db.index("public Set<String> getPendingFaceApplyTaskEmployeeIds(String operation)")
        end = self.db.index("public", start + 10)
        body = self.db[start:end]
        self.assertIn("state='PENDING'", body)

    def test_runtime_snapshot_is_taken_under_face_library_lock_and_checks_native_size(self) -> None:
        self.assertIn("public RuntimeSnapshot snapshotRuntime()", self.face)
        start = self.face.index("public RuntimeSnapshot snapshotRuntime()")
        end = self.face.index("public", start + 10)
        body = self.face[start:end]
        self.assertIn("synchronized (faceLibraryLock)", body)
        self.assertIn("faceSearch.getSize()", body)
        self.assertIn("new HashSet<>(empToIntId.keySet())", body)
        self.assertIn("mappingConsistent", body)

    def test_reconcile_requires_rebuild_for_untrusted_runtime(self) -> None:
        self.assertIn("snapshot.state != FaceManager.FaceLibraryState.READY", self.reconciler)
        self.assertIn("snapshot.nativeSize != snapshot.loadedFaceCount", self.reconciler)
        self.assertIn("!snapshot.mappingConsistent", self.reconciler)
        self.assertIn("return ReconcileResult.rebuild", self.reconciler)

    def test_pending_tasks_explain_expected_transitional_differences(self) -> None:
        self.assertIn("mustBeLoaded.removeAll(pendingUpserts)", self.reconciler)
        self.assertIn("allowedRuntime.addAll(pendingUpserts)", self.reconciler)
        self.assertIn("allowedRuntime.addAll(pendingRemoves)", self.reconciler)
        self.assertIn("snapshot.employeeIds.containsAll(mustBeLoaded)", self.reconciler)
        self.assertIn("allowedRuntime.containsAll(snapshot.employeeIds)", self.reconciler)

    def test_reconcile_requires_valid_persisted_feature_for_runtime_desired_faces(self) -> None:
        self.assertIn("getFaceRuntimeEmployeeIdsMissingValidFeature", self.db)
        self.assertIn("db.getFaceRuntimeEmployeeIdsMissingValidFeature", self.reconciler)
        self.assertIn("FaceManager.get().getFeatureSchemaVersion()", self.reconciler)
        self.assertIn("if (!missingFeatures.isEmpty())", self.reconciler)
        start = self.db.index("public Set<String> getFaceRuntimeEmployeeIdsMissingValidFeature")
        end = self.db.index("public", start + 10)
        body = self.db[start:end]
        self.assertIn("feature_schema_version=?", body)
        self.assertIn("LENGTH(f.feature)=512", body)
        self.assertIn("f.face_version=e.face_version", body)
        self.assertIn("state='PENDING'", body)
        self.assertIn("operation='UPSERT'", body)

    def test_punch_preparation_skips_full_rebuild_only_when_reconcile_is_consistent(self) -> None:
        start = self.app.index("private void runPunchPreparation()")
        end = self.app.index("private boolean waitForFaceSdkReady()", start)
        body = self.app[start:end]
        self.assertIn("FaceLibraryReconciler.get().reconcile(this)", body)
        self.assertIn("reconcileResult.isConsistent()", body)
        self.assertIn("markPunchRecognitionReady(\"准备完成，可以开始打卡\")", body)
        self.assertIn("rebuildLocalFaceLibrary(this)", body)
        self.assertLess(body.index("reconcileResult.isConsistent()"), body.index("rebuildLocalFaceLibrary(this)"))

class FaceFeatureBulkRebuildV13ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")

    def test_database_bulk_loads_face_features_in_bounded_chunks(self) -> None:
        self.assertIn("FACE_FEATURE_QUERY_BATCH_SIZE", self.db)
        self.assertIn("public Map<String, FaceFeatureCacheEntry> getFaceFeaturesByEmployeeIds(", self.db)
        start = self.db.index("public Map<String, FaceFeatureCacheEntry> getFaceFeaturesByEmployeeIds(")
        end = self.db.index("public", start + 10)
        body = self.db[start:end]
        self.assertIn("FACE_FEATURE_QUERY_BATCH_SIZE", body)
        self.assertIn("WHERE emp_id IN (", body)
        self.assertIn("SELECT emp_id, face_version", body)
        self.assertIn("feature_schema_version", body)
        self.assertIn("feature", body)

    def test_bulk_feature_entry_keeps_metadata_needed_for_in_memory_validation(self) -> None:
        self.assertIn("public static final class FaceFeatureCacheEntry", self.db)
        for token in [
            "public final int faceVersion;",
            "public final String imageSha256;",
            "public final int featureSchemaVersion;",
            "public final byte[] feature;",
        ]:
            self.assertIn(token, self.db)

    def test_full_rebuild_loads_feature_cache_before_per_employee_preparation(self) -> None:
        start = self.face.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.face.index("public RegisterResult registerFace", start)
        body = self.face[start:end]
        self.assertIn("db.getFaceFeaturesByEmployeeIds(", body)
        self.assertIn("Map<String, DatabaseHelper.FaceFeatureCacheEntry>", body)
        bulk_pos = body.index("db.getFaceFeaturesByEmployeeIds(")
        helper_pos = body.index("loadFeatureForRebuild(", bulk_pos)
        self.assertLess(bulk_pos, helper_pos)
        self.assertNotIn("db.getValidFaceFeature(", body)

    def test_rebuild_helper_validates_bulk_cached_metadata_before_fallback(self) -> None:
        start = self.face.index("private byte[] loadFeatureForRebuild(")
        end = self.face.index("private void persistExtractedFeature(", start)
        body = self.face[start:end]
        self.assertIn("DatabaseHelper.FaceFeatureCacheEntry cached", body)
        self.assertIn("cached.faceVersion == emp.faceVersion", body)
        self.assertIn("cached.featureSchemaVersion == FACE_FEATURE_SCHEMA_VERSION", body)
        self.assertIn("cached.feature != null && cached.feature.length == 512", body)
        self.assertIn("normalizeFaceSha256(cached.imageSha256)", body)
        self.assertIn("normalizeFaceSha256(emp.faceImageSha256)", body)
        cache_pos = body.index("DatabaseHelper.FaceFeatureCacheEntry cached")
        image_pos = body.index("FaceFileManager.getFaceImagePath")
        self.assertLess(cache_pos, image_pos)

    def test_rebuild_cache_miss_still_regenerates_and_persists_feature(self) -> None:
        start = self.face.index("private byte[] loadFeatureForRebuild(")
        end = self.face.index("private void persistExtractedFeature(", start)
        body = self.face[start:end]
        for token in [
            "extractFeatureFromFile(imagePath, emp.id)",
            "stats.regenerated += 1;",
            "db.upsertFaceFeature(",
        ]:
            self.assertIn(token, body)

class FaceRebuildBulkMetadataV14ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db = read_text("app/src/main/java/com/punch/app/db/DatabaseHelper.java")
        self.face = read_text("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")

    def test_database_bulk_loads_pending_apply_employee_ids(self) -> None:
        self.assertIn("public Set<String> getPendingFaceApplyEmployeeIds(String operation)", self.db)
        start = self.db.index("public Set<String> getPendingFaceApplyEmployeeIds(String operation)")
        end = self.db.index("public", start + 10)
        body = self.db[start:end]
        self.assertIn("SELECT emp_id FROM face_apply_tasks", body)
        self.assertIn("operation=?", body)
        self.assertIn("state='PENDING'", body)

    def test_database_bulk_gets_or_creates_stable_face_sdk_ids(self) -> None:
        self.assertIn("FACE_SDK_ID_QUERY_BATCH_SIZE", self.db)
        self.assertIn("public Map<String, Integer> getOrCreateFaceSdkIds(", self.db)
        start = self.db.index("public Map<String, Integer> getOrCreateFaceSdkIds(")
        end = self.db.index("private", start + 10)
        body = self.db[start:end]
        self.assertIn("faceSdkIdAllocationLock", body)
        self.assertIn("beginTransaction()", body)
        self.assertIn("findFaceSdkIds", body)
        self.assertIn("findMaxFaceSdkId", body)
        self.assertIn("Integer.MAX_VALUE", body)
        self.assertIn("insertFaceSdkId(db, employeeId, nextId)", body)

    def test_full_rebuild_preloads_pending_upsert_set_once(self) -> None:
        start = self.face.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.face.index("public RegisterResult registerFace", start)
        body = self.face[start:end]
        self.assertIn("db.getPendingFaceApplyEmployeeIds(", body)
        self.assertIn("pendingRuntimeUpsertEmployeeIds.contains(emp.id)", body)
        self.assertNotIn("db.hasPendingFaceApplyTask(", body)

    def test_full_rebuild_bulk_allocates_sdk_ids_outside_employee_loop(self) -> None:
        start = self.face.index("public boolean rebuildFaceLibrarySync(Context context)")
        end = self.face.index("public RegisterResult registerFace", start)
        body = self.face[start:end]
        self.assertIn("db.getOrCreateFaceSdkIds(", body)
        self.assertIn("Map<String, Integer> rebuildSdkIds", body)
        self.assertIn("rebuildSdkIds.get(emp.id)", body)
        self.assertNotIn("db.getOrCreateFaceSdkId(emp.id)", body)
        bulk_pos = body.index("db.getOrCreateFaceSdkIds(")
        native_pos = body.index("synchronized (faceLibraryLock)")
        self.assertLess(bulk_pos, native_pos)

    def test_v14_does_not_require_database_schema_bump(self) -> None:
        self.assertIn("DB_VERSION = 12", self.constants)

class FaceDeliveryV15SingleEventAckContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.api = read_text("app/src/main/java/com/punch/app/network/ApiService.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")

    def _body(self) -> str:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        return self.sync[start:end]

    def test_employee_sync_keeps_existing_fields_and_200_page_size(self) -> None:
        self.assertIn("private static final int EMPLOYEE_SYNC_PAGE_SIZE = 200;", self.api)
        start = self.api.index("public static ApiResult<EmployeeSyncData> syncEmployees(int page)")
        end = self.api.index("public static ApiResult<Void> reportEventResult", start)
        body = self.api[start:end]
        for token in [
            'body.put("device_id"',
            'body.put("page", page);',
            'body.put("page_size", EMPLOYEE_SYNC_PAGE_SIZE);',
            'body.put("op_status", 1);',
        ]:
            self.assertIn(token, body)
        for forbidden in ["batch_cursor", "batch_size", "event_cursor"]:
            self.assertNotIn(forbidden, body)

    def test_person_changed_pages_forward_without_intermediate_ack(self) -> None:
        body = self._body()
        self.assertIn("int page = 1;", body)
        self.assertIn("ApiService.syncEmployees(page)", body)
        self.assertIn("page += 1;", body)
        self.assertNotIn("ApiService.syncEmployees(1)", body)
        self.assertEqual(1, body.count("ApiService.reportEventResult("))

    def test_batches_accumulate_results_and_ack_once_after_paging_finishes(self) -> None:
        body = self._body()
        self.assertIn("List<EventResultDto.EmployeeResult> eventEmployeeResults = new ArrayList<>();", body)
        self.assertIn("eventEmployeeResults.addAll(batch.employeeResults);", body)
        self.assertIn("if (!data.hasMore) {", body)
        self.assertIn("break;", body)
        ack = body.index("ApiService.reportEventResult(")
        page_inc = body.index("page += 1;")
        self.assertGreater(ack, page_inc)
        self.assertIn("eventEmployeeResults", body[ack:])

    def test_face_apply_is_triggered_per_durable_batch_before_final_event_ack(self) -> None:
        body = self._body()
        commit_processing = body.index("EmployeeBatchOutcome batch = processEmployeeEventBatch(")
        worker = body.index("FaceApplyWorker.get().trigger(context);", commit_processing)
        ack = body.index("ApiService.reportEventResult(")
        self.assertLess(commit_processing, worker)
        self.assertLess(worker, ack)

    def test_backpressure_does_not_abort_single_ack_event_mid_paging(self) -> None:
        body = self._body()
        self.assertNotIn("shouldPauseEmployeeBatchFetch(context)", body)
        self.assertNotIn("FACE_APPLY_BACKLOG_HIGH_WATERMARK", body)
        self.assertNotIn("FACE_APPLY_BACKLOG_LOW_WATERMARK", body)
        self.assertNotIn("Thread.sleep", body)

    def test_intermediate_fetch_or_durable_commit_failure_returns_without_event_ack(self) -> None:
        body = self._body()
        ack = body.index("ApiService.reportEventResult(")
        fetch_fail = body.index("if (!result.success || result.data == null)")
        durable_fail = body.index("if (!batch.durableCommitSucceeded)")
        self.assertLess(fetch_fail, ack)
        self.assertLess(durable_fail, ack)
        self.assertIn("return false;", body[fetch_fail:durable_fail])
        self.assertIn("return false;", body[durable_fail:ack])

    def test_final_event_ack_uses_original_cursor_and_all_employee_results(self) -> None:
        body = self._body()
        ack = body.index("ApiService.reportEventResult(")
        tail = body[ack:]
        self.assertIn("event.cursor", tail)
        self.assertIn("event.eventType", tail)
        self.assertIn("true", tail)
        self.assertIn("eventEmployeeResults", tail)
        self.assertIn("事件结果已回传", tail)


class FaceDeliveryV16PageSize200ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.api = read_text("app/src/main/java/com/punch/app/network/ApiService.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")

    def test_employee_sync_page_size_is_200_without_request_field_changes(self) -> None:
        self.assertIn("private static final int EMPLOYEE_SYNC_PAGE_SIZE = 200;", self.api)
        start = self.api.index("public static ApiResult<EmployeeSyncData> syncEmployees(int page)")
        end = self.api.index("public static ApiResult<Void> reportEventResult", start)
        body = self.api[start:end]
        for token in [
            'body.put("device_id"',
            'body.put("page", page);',
            'body.put("page_size", EMPLOYEE_SYNC_PAGE_SIZE);',
            'body.put("op_status", 1);',
        ]:
            self.assertIn(token, body)
        for forbidden in ["batch_cursor", "batch_size", "event_cursor"]:
            self.assertNotIn(forbidden, body)

    def test_single_event_ack_paging_contract_is_preserved(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("int page = 1;", body)
        self.assertIn("ApiService.syncEmployees(page)", body)
        self.assertIn("page += 1;", body)
        self.assertEqual(1, body.count("ApiService.reportEventResult("))
        self.assertIn("eventEmployeeResults.addAll(batch.employeeResults);", body)

class EmployeeSyncProgressV17ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.app = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.fragment = read_text("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        self.layout = read_text("app/src/main/res/layout/fragment_punch.xml")

    def test_application_exposes_separate_employee_sync_progress_in_snapshot(self) -> None:
        self.assertIn("private volatile EmployeeSyncProgress employeeSyncProgress", self.app)
        self.assertIn("public final EmployeeSyncProgress employeeSyncProgress;", self.app)
        self.assertIn("employeeSyncProgress", self.app[self.app.index("public PunchStatusSnapshot getPunchStatusSnapshot()"):])
        self.assertIn("public static final class EmployeeSyncProgress", self.app)

    def test_employee_sync_duration_uses_monotonic_clock_and_formats_completion_text(self) -> None:
        self.assertIn("import android.os.SystemClock;", self.app)
        self.assertIn("SystemClock.elapsedRealtime()", self.app)
        self.assertIn("formatEmployeeSyncDuration", self.app)
        self.assertIn('"人员更新完成 · "', self.app)
        self.assertIn('" 人 · 用时 "', self.app)
        for token in ['"秒"', '"分"', '"小时"']:
            self.assertIn(token, self.app)

    def test_person_changed_publishes_start_batch_reporting_and_completion_progress(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        for token in [
            "app.beginEmployeeSyncProgress(event.cursor)",
            "app.updateEmployeeSyncBatchProcessing(",
            "app.markEmployeeSyncBatchCommitted(",
            "app.markEmployeeSyncReporting(",
            "app.completeEmployeeSyncProgress(",
        ]:
            self.assertIn(token, body)
        ack = body.index("ApiService.reportEventResult(")
        reporting = body.index("app.markEmployeeSyncReporting(")
        completed = body.index("app.completeEmployeeSyncProgress(")
        self.assertLess(reporting, ack)
        self.assertGreater(completed, ack)

    def test_person_changed_failure_keeps_main_punch_readiness_separate(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("app.failEmployeeSyncProgress(", body)
        self.assertNotIn("app.markPunchRecognitionFailed(", body)
        self.assertNotIn("app.beginPunchDataPreparation(", body)

    def test_punch_layout_has_dedicated_employee_sync_progress_area(self) -> None:
        for token in [
            'android:id="@+id/layout_employee_sync_progress"',
            'android:id="@+id/tv_employee_sync_title"',
            'android:id="@+id/tv_employee_sync_detail"',
            'android:id="@+id/progress_employee_sync"',
            'style="?android:attr/progressBarStyleHorizontal"',
        ]:
            self.assertIn(token, self.layout)

    def test_fragment_renders_employee_sync_progress_without_replacing_current_status(self) -> None:
        for token in [
            "layoutEmployeeSyncProgress",
            "tvEmployeeSyncTitle",
            "tvEmployeeSyncDetail",
            "progressEmployeeSync",
            "renderEmployeeSyncProgress(snapshot.employeeSyncProgress)",
            "private void renderEmployeeSyncProgress(PunchApplication.EmployeeSyncProgress progress)",
        ]:
            self.assertIn(token, self.fragment)
        render_start = self.fragment.index("private void renderEmployeeSyncProgress(")
        render_end = self.fragment.index("private void", render_start + 10)
        body = self.fragment[render_start:render_end]
        self.assertIn("progress.progressPercent", body)
        self.assertIn("progress.title", body)
        self.assertIn("progress.detail", body)
        self.assertNotIn("tvPunchStatusCurrent.setText", body)

    def test_progress_history_records_batch_milestones_not_per_employee_noise(self) -> None:
        self.assertIn("markEmployeeSyncBatchCommitted", self.app)
        start = self.app.index("public void markEmployeeSyncBatchCommitted")
        end = self.app.index("public void", start + 10)
        body = self.app[start:end]
        self.assertIn("addHistoryLocked", body)
        self.assertIn("累计", body)
        self.assertNotIn("for (", body)

    def test_resetting_punch_status_timeline_clears_stale_employee_sync_progress(self) -> None:
        start = self.app.index("public void resetPunchStatusTimeline")
        end = self.app.index("public void addPunchStatusListener", start)
        body = self.app[start:end]
        self.assertIn("employeeSyncProgress = null;", body)
        self.assertIn("employeeSyncStartedAtElapsedMs = 0L;", body)


class EmployeeSyncPerItemProgressV18ContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.app = read_text("app/src/main/java/com/punch/app/PunchApplication.java")
        self.sync = read_text("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.reg = read_text("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")

    def test_application_tracks_each_employee_with_300ms_ui_throttle(self) -> None:
        self.assertIn("EMPLOYEE_SYNC_UI_THROTTLE_MS = 300L", self.app)
        self.assertIn("employeeSyncLastUiPublishElapsedMs", self.app)
        self.assertIn("public void updateEmployeeSyncItemProgress(", self.app)
        start = self.app.index("public void updateEmployeeSyncItemProgress(")
        end = self.app.index("public void", start + 10)
        body = self.app[start:end]
        self.assertIn('" · 当前 " + safeBatchProcessed + "/" + safeBatchSize', body)
        self.assertIn('" · 累计 " + safeProcessedTotal + " 人"', body)
        self.assertIn("now - employeeSyncLastUiPublishElapsedMs >= EMPLOYEE_SYNC_UI_THROTTLE_MS", body)
        self.assertIn("safeBatchProcessed <= 1", body)
        self.assertIn("safeBatchProcessed >= safeBatchSize", body)
        self.assertNotIn("addHistoryLocked", body)

    def test_item_progress_moves_horizontal_bar_inside_current_page(self) -> None:
        self.assertIn("resolveEmployeeSyncItemProgressPercent", self.app)
        start = self.app.index("private int resolveEmployeeSyncItemProgressPercent")
        end = self.app.index("public static String formatEmployeeSyncDuration", start)
        body = self.app[start:end]
        self.assertIn("batchProcessed", body)
        self.assertIn("batchSize", body)
        self.assertIn("currentPage", body)
        self.assertIn("totalPages", body)

    def test_face_preparation_reports_each_completed_employee(self) -> None:
        self.assertIn("public interface PreparedProgressCallback", self.reg)
        self.assertIn("PreparedProgressCallback progressCallback", self.reg)
        start = self.reg.index("private List<PreparedFaceResult> prepareEmployeesInternal")
        end = self.reg.index("private PreparedFaceResult prepareSingleForPersistence", start)
        body = self.reg[start:end]
        self.assertIn("PreparedFaceResult result = prepareSingleForPersistence", body)
        self.assertIn("progressCallback.onPrepared(result)", body)

    def test_sync_coordinator_counts_fast_and_feature_paths_once_each(self) -> None:
        self.assertIn("interface EmployeeBatchProgressCallback", self.sync)
        self.assertIn("AtomicInteger", self.sync)
        start = self.sync.index("private EmployeeBatchOutcome processEmployeeEventBatch(")
        end = self.sync.index("private List<EmployeeSyncData.ChangeItem> coalesceLatestEmployeeChanges", start)
        body = self.sync[start:end]
        self.assertIn("markEmployeeBatchItemProcessed", body)
        self.assertIn("waitForFacePreparation(", body)
        self.assertIn("progressCallback", body)
        self.assertIn("rawBatchSize", body)
        self.assertIn("effectiveChanges.size()", body)

    def test_person_changed_publishes_per_item_progress_with_page_offset(self) -> None:
        start = self.sync.index("private boolean syncEmployeesEventInBatches(Context context,")
        end = self.sync.index("private EventProcessingOutcome handleEvent", start)
        body = self.sync[start:end]
        self.assertIn("final int processedBeforePage = eventEmployeeResults.size();", body)
        self.assertIn("app.updateEmployeeSyncItemProgress(", body)
        self.assertIn("processedBeforePage + batchProcessed", body)
        self.assertIn("EmployeeBatchOutcome batch = processEmployeeEventBatch(", body)
        self.assertIn("data.changeItems,", body)

    def test_reset_clears_throttle_timestamp(self) -> None:
        start = self.app.index("public void resetPunchStatusTimeline")
        end = self.app.index("public void addPunchStatusListener", start)
        body = self.app[start:end]
        self.assertIn("employeeSyncLastUiPublishElapsedMs = 0L;", body)


class OtaDurableRetryContractTest(unittest.TestCase):
    def test_retry_state_is_persisted_with_target_and_next_time(self) -> None:
        constants = read_text("app/src/main/java/com/punch/app/utils/Constants.java")
        session = read_text("app/src/main/java/com/punch/app/utils/SessionManager.java")
        for token in [
            "KEY_UPDATE_RETRY_PENDING",
            "KEY_UPDATE_RETRY_COUNT",
            "KEY_UPDATE_RETRY_NEXT_AT",
            "KEY_UPDATE_RETRY_LAST_ERROR",
            "KEY_UPDATE_RETRY_TARGET_VERSION",
            "KEY_UPDATE_RETRY_APK_URL",
        ]:
            self.assertIn(token, constants)
        for token in [
            "saveUpdateRetryState",
            "clearUpdateRetryState",
            "isUpdateRetryPending",
            "getUpdateRetryCount",
            "getUpdateRetryNextAt",
            "getUpdateRetryLastError",
            "getUpdateRetryTargetVersion",
            "getUpdateRetryApkUrl",
        ]:
            self.assertIn(token, session)
        save = re.search(r"(?s)void\s+saveUpdateRetryState\s*\(.*?\)\s*\{(.*?)\n\s*\}", session)
        self.assertIsNotNone(save)
        self.assertIn(".commit()", save.group(1))

    def test_retry_policy_has_unbounded_backoff_schedule(self) -> None:
        policy_path = ROOT / "app/src/main/java/com/punch/app/utils/UpdateRetryPolicy.java"
        self.assertTrue(policy_path.is_file(), policy_path)
        policy = policy_path.read_text(encoding="utf-8")
        for delay in ["60_000L", "5 * 60_000L", "15 * 60_000L", "30 * 60_000L", "60 * 60_000L", "3 * 60 * 60_000L"]:
            self.assertIn(delay, policy)
        self.assertNotIn("MAX_RETRY", policy)

    def test_retry_receiver_uses_alarm_and_restores_after_boot(self) -> None:
        receiver_path = ROOT / "app/src/main/java/com/punch/app/receiver/UpdateRetryReceiver.java"
        self.assertTrue(receiver_path.is_file(), receiver_path)
        receiver = receiver_path.read_text(encoding="utf-8")
        for token in [
            "AlarmManager",
            "setAndAllowWhileIdle",
            "ACTION_UPDATE_RETRY",
            "Intent.ACTION_BOOT_COMPLETED",
            "restorePersistedRetry",
            "startBackgroundUpdateIfEligible",
        ]:
            self.assertIn(token, receiver)

        manifest = read_text("app/src/main/AndroidManifest.xml")
        self.assertIn("android.permission.RECEIVE_BOOT_COMPLETED", manifest)
        self.assertIn(".receiver.UpdateRetryReceiver", manifest)
        self.assertIn("android.intent.action.BOOT_COMPLETED", manifest)

    def test_update_manager_schedules_transient_failures_and_clears_success(self) -> None:
        manager = read_text("app/src/main/java/com/punch/app/utils/UpdateManager.java")
        for token in [
            "scheduleDurableRetry",
            "UpdateRetryReceiver.schedule",
            "UpdateRetryReceiver.cancel",
            "UpdateRetryPolicy.delayMsForFailureCount",
            "download_failed",
            "download_crashed",
            "update_dir_unavailable",
            "old_apk_delete_failed",
            "install_submit_failed",
            "install_pending_timeout",
        ]:
            self.assertIn(token, manager)
        # Validation errors are permanent: do not schedule retry inside that branch.
        validation = re.search(
            r"(?s)if\s*\(!validation\.success\)\s*\{(.*?)\n\s*\}", manager
        )
        self.assertIsNotNone(validation)
        self.assertNotIn("scheduleDurableRetry", validation.group(1))

    def test_new_target_resets_stale_retry_generation(self) -> None:
        manager = read_text("app/src/main/java/com/punch/app/utils/UpdateManager.java")
        self.assertIn("reconcileRetryGeneration", manager)
        self.assertIn("getUpdateRetryTargetVersion", manager)
        self.assertIn("getUpdateRetryApkUrl", manager)
        self.assertIn("clearUpdateRetryState", manager)

    def test_successful_install_clears_retry_and_alarm(self) -> None:
        manager = read_text("app/src/main/java/com/punch/app/utils/UpdateManager.java")
        success_branch = re.search(
            r"(?s)if\s*\(status\s*==\s*PackageInstaller\.STATUS_SUCCESS\s*\|\|\s*isInstalledVersionAtTarget\(context\)\)\s*\{(.*?)\n\s*\}",
            manager,
        )
        self.assertIsNotNone(success_branch)
        self.assertIn("clearDurableRetry", success_branch.group(1))


class OtaAttemptWatchdogContractTest(unittest.TestCase):
    def test_attempt_is_armed_before_async_download_can_be_killed(self) -> None:
        manager = read_text("app/src/main/java/com/punch/app/utils/UpdateManager.java")
        self.assertIn("armAttemptWatchdog", manager)
        start_pos = manager.index("public static boolean startBackgroundUpdateIfEligible")
        arm_pos = manager.index("armAttemptWatchdog(appContext)", start_pos)
        execute_pos = manager.index("AUTO_UPDATE_EXECUTOR.execute", start_pos)
        self.assertLess(arm_pos, execute_pos)

    def test_retry_alarm_rearms_when_an_existing_attempt_is_still_running(self) -> None:
        receiver = read_text("app/src/main/java/com/punch/app/receiver/UpdateRetryReceiver.java")
        self.assertIn("boolean started = UpdateManager.startBackgroundUpdateIfEligible", receiver)
        self.assertIn("ATTEMPT_WATCHDOG_MS", receiver)
        self.assertIn("if (!started && session.isUpdateRetryPending()", receiver)
