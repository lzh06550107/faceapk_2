package com.punch.app.utils;

final class AvatarLoadPolicy {
    private AvatarLoadPolicy() {
    }

    enum RequestManagerChoice {
        VIEW,
        APPLICATION_CONTEXT,
        SKIP
    }

    static RequestManagerChoice chooseRequestManager(boolean clearing, boolean ownerDestroyed) {
        if (!ownerDestroyed) {
            return RequestManagerChoice.VIEW;
        }
        return clearing ? RequestManagerChoice.APPLICATION_CONTEXT : RequestManagerChoice.SKIP;
    }

    /**
     * Android filesystem paths must never be treated as backend-relative HTTP paths.
     *
     * <p>The app stores face images under /data/user/... (and legacy Android installs may use
     * /data/data/...). External-storage absolute paths are local as well. If such a file is
     * missing, AvatarLoader must fall back locally instead of requesting baseUrl + source.</p>
     */
    static boolean isAndroidLocalAbsolutePath(String source) {
        if (source == null) {
            return false;
        }
        String clean = source.trim();
        return clean.startsWith("/data/user/")
                || clean.startsWith("/data/data/")
                || clean.startsWith("/storage/")
                || clean.startsWith("/sdcard/")
                || clean.startsWith("/mnt/");
    }
}
