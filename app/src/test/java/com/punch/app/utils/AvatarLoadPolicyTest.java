package com.punch.app.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AvatarLoadPolicyTest {
    @Test
    public void androidPrivateFacePathIsAlwaysLocal() {
        assertEquals(
                true,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "/data/user/0/com.punch.app/files/faces/a4jGBL.jpg"));
        assertEquals(
                true,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "/data/data/com.punch.app/files/faces/legacy.jpg"));
    }

    @Test
    public void externalAbsolutePathsAreLocal() {
        assertEquals(
                true,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "/storage/emulated/0/Download/avatar.jpg"));
        assertEquals(
                true,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "/sdcard/avatar.jpg"));
    }

    @Test
    public void backendRelativeImagePathIsNotMistakenForLocalFile() {
        assertEquals(
                false,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "/uploads/faces/a4jGBL.jpg"));
        assertEquals(
                false,
                AvatarLoadPolicy.isAndroidLocalAbsolutePath(
                        "uploads/faces/a4jGBL.jpg"));
    }

    @Test
    public void clearingAvatarAfterActivityDestroyedUsesApplicationContext() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.APPLICATION_CONTEXT,
                AvatarLoadPolicy.chooseRequestManager(true, true));
    }

    @Test
    public void loadingAvatarAfterActivityDestroyedDoesNotStartRequest() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.SKIP,
                AvatarLoadPolicy.chooseRequestManager(false, true));
    }

    @Test
    public void activeActivityUsesViewLifecycleForImageRequests() {
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.VIEW,
                AvatarLoadPolicy.chooseRequestManager(false, false));
        assertEquals(
                AvatarLoadPolicy.RequestManagerChoice.VIEW,
                AvatarLoadPolicy.chooseRequestManager(true, false));
    }
}
