import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class SystemNtpConfiguratorStaticTest(unittest.TestCase):
    def test_manifest_requests_write_secure_settings_and_writes_are_centralized(self):
        manifest = read("app/src/main/AndroidManifest.xml")
        self.assertIn("android.permission.WRITE_SECURE_SETTINGS", manifest)

        configurator = read("app/src/main/java/com/punch/app/utils/SystemNtpConfigurator.java")
        self.assertIn('KEY_NTP_SERVER = "ntp_server"', configurator)
        self.assertIn("Settings.Global.putString", configurator)
        self.assertIn("Settings.Global.getString", configurator)
        self.assertIn("setAutoTimeEnabled", configurator)
        self.assertIn("UserManager.DISALLOW_CONFIG_DATE_TIME", configurator)
        self.assertIn("rollback", configurator.lower())

        activity = read("app/src/main/java/com/punch/app/activity/SetupWizardActivity.java")
        self.assertNotIn("Settings.Global.putString", activity)
        apply_start = configurator.index("public static ApplyResult apply")
        snapshot_pos = configurator.index("String previousServer", apply_start)
        try_pos = configurator.index("try {", apply_start)
        self.assertLess(try_pos, snapshot_pos)


class SetupWizardNtpStaticTest(unittest.TestCase):
    def test_setup_wizard_has_ntp_page_and_async_ntp_actions(self):
        activity = read("app/src/main/java/com/punch/app/activity/SetupWizardActivity.java")
        layout = read("app/src/main/res/layout/activity_setup_wizard.xml")

        self.assertIn("private static final int PAGE_NTP = 1;", activity)
        self.assertIn("private static final int PAGE_SERVER = 2;", activity)
        self.assertIn("Executors.newSingleThreadExecutor()", activity)
        self.assertIn("ntpExecutor.shutdownNow()", activity)
        self.assertIn("NtpProbeClient.probe", activity)
        self.assertIn("SystemNtpConfigurator.apply", activity)
        self.assertIn("etNtpServer.setText(SystemNtpPolicy.DEFAULT_TARGET_SERVER)", activity)
        self.assertNotIn("Settings.Global.putString", activity)

        for view_id in (
            "page_ntp",
            "tv_ntp_capability",
            "tv_ntp_current_server",
            "et_ntp_server",
            "btn_test_ntp",
            "btn_apply_ntp",
            "tv_ntp_status",
            "btn_ntp_prev",
            "btn_ntp_next",
        ):
            self.assertIn(f'android:id="@+id/{view_id}"', layout)
        self.assertIn("第 2 步 · 系统时间 / NTP", layout)
        self.assertIn("第 3 步 · 配置服务器", layout)


if __name__ == "__main__":
    unittest.main()
