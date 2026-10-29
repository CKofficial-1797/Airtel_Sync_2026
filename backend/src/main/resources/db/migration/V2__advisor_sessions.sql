-- Phase 4: AI advisor sessions and messages (the ai-service is stateless; Spring keeps the conversation)

CREATE TABLE advisor_session (
    id                        UUID         PRIMARY KEY,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ui_language               VARCHAR(10),
    detected_language         VARCHAR(20),
    stage                     VARCHAR(30)  NOT NULL DEFAULT 'COLLECTING',
    profile_json              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_recommendation_json  JSONB,
    last_quote_json           JSONB,
    CONSTRAINT advisor_session_stage_chk CHECK (stage IN
        ('COLLECTING', 'RECOMMENDED', 'CUSTOMISING', 'READY_FOR_CHECKOUT', 'OUT_OF_SCOPE'))
);

CREATE INDEX idx_advisor_session_stage ON advisor_session (stage);

CREATE TABLE advisor_message (
    id               BIGSERIAL    PRIMARY KEY,
    session_id       UUID         NOT NULL REFERENCES advisor_session (id) ON DELETE CASCADE,
    role             VARCHAR(10)  NOT NULL,
    content          TEXT         NOT NULL,
    tool_trace_json  JSONB,
    guard_json       JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT advisor_message_role_chk CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX idx_advisor_message_session ON advisor_message (session_id, id);
