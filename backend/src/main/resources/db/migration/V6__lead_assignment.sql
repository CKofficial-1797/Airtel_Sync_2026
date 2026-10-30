-- Phase 9: back-office. Who follows up on a lead (historical lead, unclosed advisor session or stuck order),
-- plus a history of every change. lead_ref is lead_id for HISTORICAL and the UUID for SESSION and ORDER.

CREATE TABLE lead_assignment (
    id              BIGSERIAL    PRIMARY KEY,
    lead_kind       VARCHAR(12)  NOT NULL,
    lead_ref        VARCHAR(40)  NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    status          VARCHAR(12)  NOT NULL DEFAULT 'NEW',
    assignee_label  TEXT,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT lead_assignment_lead_uq UNIQUE (lead_kind, lead_ref),
    CONSTRAINT lead_assignment_kind_chk CHECK (lead_kind IN ('HISTORICAL', 'SESSION', 'ORDER')),
    CONSTRAINT lead_assignment_status_chk CHECK (status IN ('NEW', 'CONTACTED', 'CONVERTED', 'LOST')),
    CONSTRAINT lead_assignment_channel_chk CHECK (channel IN ('DIGITAL_NUDGE', 'INSIDE_SALES', 'FIELD_SALES', 'CHANNEL_PARTNER'))
);

CREATE TABLE lead_assignment_event (
    id              BIGSERIAL    PRIMARY KEY,
    lead_kind       VARCHAR(12)  NOT NULL,
    lead_ref        VARCHAR(40)  NOT NULL,
    channel         VARCHAR(20)  NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    assignee_label  TEXT,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_lead_assignment_event_lead ON lead_assignment_event (lead_kind, lead_ref, id);
