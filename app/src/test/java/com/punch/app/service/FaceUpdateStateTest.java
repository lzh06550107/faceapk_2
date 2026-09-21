package com.punch.app.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FaceUpdateStateTest {
    @Test
    public void backgroundProgressDoesNotImplyPunchReadinessState() {
        FaceUpdateState state = new FaceUpdateState();
        assertFalse(state.isUpdating());
        state.begin(600, "正在后台更新人脸");
        assertTrue(state.isUpdating());
        assertEquals(600, state.getTotal());
        state.progress(127);
        assertEquals(127, state.getCompleted());
        state.finish("后台人脸更新完成");
        assertFalse(state.isUpdating());
        assertEquals("后台人脸更新完成", state.getStatus());
    }
}
