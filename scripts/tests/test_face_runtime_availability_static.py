import os
import pathlib
import unittest

ROOT = pathlib.Path(os.environ.get("FACE_STATIC_ROOT", pathlib.Path(__file__).resolve().parents[2]))


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class RuntimeAvailabilityStaticTest(unittest.TestCase):
    def test_punch_gate_uses_actual_runtime_face_library(self):
        manager = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        app = read("app/src/main/java/com/punch/app/PunchApplication.java")
        self.assertIn("public boolean isRuntimeFaceLibraryUsable()", manager)
        self.assertIn("FaceRuntimeAvailabilityPolicy.isUsable(initialized, loadedFaceCount)", manager)
        self.assertIn("return FaceManager.get().isRuntimeFaceLibraryUsable();", app)
        self.assertIn("boolean runtimeUsable = faceManager.isRuntimeFaceLibraryUsable();", app)
        self.assertIn("if (punchDataPreparing)", app)
        self.assertIn("if (runtimeUsable)", app)
        self.assertIn("markPunchRecognitionReady(\"准备完成，可以开始打卡\")", app)

    def test_incomplete_preparation_keeps_loaded_library_ready(self):
        sync = read("app/src/main/java/com/punch/app/service/SyncCoordinator.java")
        self.assertIn("FacePreparationReadinessPolicy.shouldRemainReady(false, loadedFaceCount)", sync)
        self.assertIn("人脸准备未完整完成，但已加载 ", sync)
        self.assertIn("app.markPunchRecognitionReady(STATUS_MSG_PUNCH_PARTIAL_READY);", sync)


class ValidationBatchStaticTest(unittest.TestCase):
    def test_rebuild_validation_coalesces_covering_batches(self):
        manager = read("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")
        coordinator = read("app/src/main/java/com/punch/app/face/FaceValidationBatchCoordinator.java")
        self.assertIn("validationBatchCoordinator.submit", manager)
        self.assertIn("if (!submission.isOwner())", manager)
        self.assertIn("batch.employeeIds.containsAll(requested)", coordinator)
        self.assertIn("validationBatchCoordinator.complete(submission, results)", manager)

    def test_rebuild_validation_is_exact_cache_first(self):
        manager = read("app/src/main/java/com/punch/app/face/FaceRegistrationManager.java")
        self.assertIn("db.getReusableFaceFeature(", manager)
        self.assertIn("FaceManager.FEATURE_CACHE_SCHEMA_VERSION", manager)
        self.assertIn("FaceValidationCachePolicy.canReuse(reusableFeature)", manager)
        self.assertIn("Face validation cache hit: empId=", manager)
        self.assertIn("nativeCandidates=", manager)


class V4SafetyBoundaryStaticTest(unittest.TestCase):
    def test_scheduler_guard_and_stable_match_contract_are_unchanged(self):
        manager = read("app/src/main/java/com/punch/app/face/FaceManager.java")
        fragment = read("app/src/main/java/com/punch/app/fragment/PunchFragment.java")
        scheduler = read("app/src/main/java/com/punch/app/face/FaceSdkScheduler.java")
        self.assertIn("sdkOperationGuard.call(() ->", manager)
        self.assertIn("private static final int STABLE_MATCH_REQUIRED_FRAMES = 2;", fragment)
        self.assertIn("private static final long STABLE_MATCH_MAX_GAP_MS = 1500;", fragment)
        self.assertIn("enum Priority", scheduler)
        self.assertIn("REALTIME", scheduler)
        self.assertIn("BACKGROUND", scheduler)
        self.assertIn("MAINTENANCE", scheduler)


if __name__ == "__main__":
    unittest.main()
