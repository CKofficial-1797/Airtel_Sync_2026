-- Phase 5: orders created when the owner accepts a quote. KYC (Phase 6) and tracking (Phase 8) attach to these.

CREATE SEQUENCE order_ref_seq START 1;

CREATE TABLE customer_order (
    id                  UUID         PRIMARY KEY,
    order_ref           VARCHAR(24)  NOT NULL UNIQUE,
    session_id          UUID         REFERENCES advisor_session (id) ON DELETE SET NULL,
    profile_json        JSONB        NOT NULL,
    quote_json          JSONB        NOT NULL,
    monthly_total_inr   INT          NOT NULL,
    one_time_total_inr  INT          NOT NULL,
    contract_months     INT          NOT NULL,
    status              VARCHAR(40)  NOT NULL DEFAULT 'QUOTE_ACCEPTED',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_order_session ON customer_order (session_id);
CREATE INDEX idx_customer_order_status ON customer_order (status);

CREATE TABLE order_event (
    id          BIGSERIAL    PRIMARY KEY,
    order_id    UUID         NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    status      VARCHAR(40)  NOT NULL,
    note        TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_event_order ON order_event (order_id, id);

-- Closed advisor sessions: stage CHECKOUT_DONE + link to the order (back-office: closed vs unclosed leads).
ALTER TABLE advisor_session DROP CONSTRAINT advisor_session_stage_chk;
ALTER TABLE advisor_session ADD CONSTRAINT advisor_session_stage_chk CHECK (stage IN
    ('COLLECTING', 'RECOMMENDED', 'CUSTOMISING', 'READY_FOR_CHECKOUT', 'OUT_OF_SCOPE', 'CHECKOUT_DONE'));
ALTER TABLE advisor_session ADD COLUMN order_id UUID REFERENCES customer_order (id) ON DELETE SET NULL;
