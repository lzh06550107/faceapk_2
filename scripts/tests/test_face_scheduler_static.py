import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class FaceSchedulerStaticTest(unittest.TestCase):
    def test_face_manager_routes_operations_by_priority_and_keeps_guard(self):
        src = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.assertIn("FaceSdkScheduler.shared()", src)
        self.assertIn("withRealtimeSdkOperation(\"recognize-nv21\"", src)
        self.assertIn("withRealtimeSdkOperation(\"recognize-bitmap\"", src)
        self.assertIn("withBackgroundSdkOperation(\"register-face:", src)
        self.assertIn("withBackgroundSdkOperation(\"remove-face:", src)
        self.assertIn("withMaintenanceSdkOperation(\"rebuild-face-library\"", src)
        self.assertIn("sdkOperationGuard.call(() ->", src)


if __name__ == "__main__":
    unittest.main()

class BackgroundPunchReadinessStaticTest(unittest.TestCase):
    def test_event_mode_uses_background_update_state_not_punch_preparation_gate(self):
        app = read("app/src/main/java/com/punch/app/PunchApplication.java")
        sync = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn("private final FaceUpdateState faceUpdateState = new FaceUpdateState();", app)
        self.assertIn("public boolean isFaceLibraryUpdating()", app)
        self.assertIn("beginFaceLibraryUpdate", app)
        self.assertIn("finishFaceLibraryUpdate", app)
        self.assertIn("if (!collectEventResults && app != null)", sync)
        self.assertIn("app.beginFaceLibraryUpdate", sync)

class PunchEligibilityStaticTest(unittest.TestCase):
    def test_punch_fragment_revalidates_employee_after_face_search(self):
        src = read("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        self.assertIn("PunchEmployeeEligibility.isEligible(emp)", src)
        employee_lookup = src.index("Employee emp = DatabaseHelper.get(context).getEmployee(result.empId);")
        eligibility = src.index("PunchEmployeeEligibility.isEligible(emp)")
        snapshot = src.index("PunchSnapshotHelper.capture(", employee_lookup)
        self.assertTrue(employee_lookup < eligibility < snapshot)

class HeartbeatEventDecouplingStaticTest(unittest.TestCase):
    def test_heartbeat_submits_platform_events_instead_of_running_and_acking_inline(self):
        src = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn('new PlatformEventExecutor("platform-event-worker")', src)
        start = src.index("private void applyHeartbeatEvents")
        end = src.index("private boolean reportEventOutcome", start)
        method = src[start:end]
        self.assertIn("platformEventExecutor.submit", method)
        self.assertNotIn("ApiService.reportEventResult", method)
        self.assertIn("reportEventOutcome", src)

class SafeFaceReplacementStaticTest(unittest.TestCase):
    def test_replacement_keeps_old_face_until_new_feature_is_ready_and_can_rollback(self):
        sync = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        manager = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        db = read("app/src/main/java/com/punch/app/db/DatabaseHelper.java")

        self.assertIn("faceAction == EmployeeFaceDeltaPolicy.Action.REPLACE", sync)
        self.assertNotIn('removalTargets.put(merged.id, "replace")', sync)
        self.assertNotIn("db.deleteFaceFeature(merged.id);\n                            if (existing != null && existing.faceRegistered == 1)", sync)
        self.assertIn("incoming.localFaceId = existing.localFaceId;", sync)
        self.assertIn("incoming.faceRegistered = existing.faceRegistered;", sync)

        self.assertIn("getFallbackFaceFeature", db)
        self.assertIn("byte[] previousFeature = db.getFallbackFaceFeature", manager)
        self.assertIn("rollbackCode = faceSearch.pushPersonById(intId, previousFeature)", manager)
        self.assertIn("Face register rollback succeeded", manager)
        registration_manager = read("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")
        refresh_start = registration_manager.index("public void refreshEmployee")
        refresh_end = registration_manager.index("private List<RegistrationResult>", refresh_start)
        refresh = registration_manager[refresh_start:refresh_end]
        self.assertNotIn("FaceManager.get().removeFace(emp.id)", refresh)
        self.assertNotIn("deleteFaceFeature(emp.id)", refresh)
        push_pos = manager.index("int pushCode = faceSearch.pushPersonById(intId, feature);")
        save_pos = manager.index("db.saveFaceFeature(", push_pos)
        self.assertTrue(push_pos < save_pos)

class RebuildFallbackStaticTest(unittest.TestCase):
    def test_rebuild_can_fall_back_to_last_usable_cached_feature(self):
        src = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        rebuild = src[src.index("private boolean rebuildFaceLibrarySyncExclusive"):src.index("public RegisterResult registerFace", src.index("private boolean rebuildFaceLibrarySyncExclusive"))]
        self.assertIn("db.getFallbackFaceFeature", rebuild)
        self.assertIn("Face rebuild using fallback cached feature", rebuild)

class DesiredFaceReadinessStaticTest(unittest.TestCase):
    def test_employee_delta_checks_feature_cache_for_desired_face_not_only_registered_flag(self):
        src = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn("boolean desiredFaceReady = merged.faceRegistered == 1", src)
        self.assertIn("db.getReusableFaceFeature(", src)
        self.assertIn("FaceManager.FEATURE_CACHE_SCHEMA_VERSION", src)
        self.assertIn("desiredFaceReady", src[src.index("EmployeeFaceDeltaPolicy.Action faceAction"):src.index("employeeResults.put", src.index("EmployeeFaceDeltaPolicy.Action faceAction"))])

class SafeFaceRemovalStaticTest(unittest.TestCase):
    def test_runtime_remove_reports_native_failure_before_mutating_java_mapping(self):
        manager = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        self.assertIn("public boolean removeFace(String empId)", manager)
        block = manager[manager.index("private boolean removeFaceExclusive"):manager.index("public RecognizeResult recognizeFromBitmap")]
        delete_pos = block.index("faceSearch.delPersonById")
        map_remove_pos = block.index("empToIntId.remove", delete_pos)
        self.assertLess(delete_pos, map_remove_pos)
        self.assertIn("return false", block[:map_remove_pos])

        sync = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        removal = sync[sync.index("for (String empId : removalTargets.keySet())"):sync.index("FaceRegistrationOutcome registrationOutcome", sync.index("for (String empId : removalTargets.keySet())"))]
        self.assertIn("boolean removed = FaceManager.get().removeFace(empId)", removal)
        self.assertIn("employeeResult.success = false", removal)
        self.assertIn("removalFailed", removal)
        self.assertIn("registrationOutcome.failed > 0 || removalFailed > 0", sync)
