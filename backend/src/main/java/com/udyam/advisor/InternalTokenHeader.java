package com.udyam.advisor;

/** The shared-secret header between Spring and the ai-service. */
public final class InternalTokenHeader {

    public static final String NAME = "X-Internal-Token";

    private InternalTokenHeader() {
    }
}
