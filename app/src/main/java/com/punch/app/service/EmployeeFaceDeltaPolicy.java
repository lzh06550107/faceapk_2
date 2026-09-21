package com.punch.app.service;

public final class EmployeeFaceDeltaPolicy {
    public enum Action {
        NONE,
        REGISTER,
        REPLACE,
        REMOVE
    }

    private EmployeeFaceDeltaPolicy() {
    }

    public static Action classify(boolean employeeExisted,
                                  boolean faceChanged,
                                  boolean deleted,
                                  boolean faceEnabled,
                                  boolean hasFaceImage,
                                  boolean currentlyRegistered) {
        if (deleted || !faceEnabled) {
            return employeeExisted ? Action.REMOVE : Action.NONE;
        }
        if (!hasFaceImage) {
            return Action.NONE;
        }
        if (employeeExisted && faceChanged && currentlyRegistered) {
            return Action.REPLACE;
        }
        if (!employeeExisted || faceChanged || !currentlyRegistered) {
            return Action.REGISTER;
        }
        return Action.NONE;
    }
}
