-- Phase 6: Smart KYC. Consent (DPDP Act 2023), extracted document fields and validation checks per order.
-- Raw images are never stored: only the sha256, the quality metrics and the extracted fields.

ALTER TABLE customer_order ADD COLUMN estimated_activation_date DATE;

CREATE TABLE kyc_consent (
    id              BIGSERIAL    PRIMARY KEY,
    order_id        UUID         NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    purpose         VARCHAR(40)  NOT NULL,
    granted         BOOLEAN      NOT NULL,
    notice_version  VARCHAR(20)  NOT NULL,
    language        VARCHAR(5)   NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    withdrawn_at    TIMESTAMPTZ
);

CREATE INDEX idx_kyc_consent_order ON kyc_consent (order_id) WHERE withdrawn_at IS NULL;

CREATE TABLE kyc_document (
    id                 BIGSERIAL    PRIMARY KEY,
    order_id           UUID         NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    doc_type           VARCHAR(30)  NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    detected_doc_type  VARCHAR(30),
    file_sha256        CHAR(64)     NOT NULL,
    blur_score         NUMERIC(10, 1),
    skew_deg           NUMERIC(6, 2),
    deskewed           BOOLEAN      NOT NULL DEFAULT false,
    extracted_json     JSONB,
    model              VARCHAR(60),
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT kyc_document_order_type_uq UNIQUE (order_id, doc_type),
    CONSTRAINT kyc_document_type_chk CHECK (doc_type IN
        ('GST_CERTIFICATE', 'UDYAM_CERTIFICATE', 'SIGNATORY_ID', 'ADDRESS_PROOF')),
    CONSTRAINT kyc_document_status_chk CHECK (status IN ('OK', 'UNREADABLE', 'WRONG_DOC_TYPE'))
);

CREATE TABLE kyc_check (
    id            BIGSERIAL    PRIMARY KEY,
    order_id      UUID         NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    run_id        UUID         NOT NULL,
    check_code    VARCHAR(4)   NOT NULL,
    check_name    VARCHAR(40)  NOT NULL,
    status        VARCHAR(10)  NOT NULL,
    severity      VARCHAR(10)  NOT NULL,
    fixable       BOOLEAN      NOT NULL,
    message_en    TEXT         NOT NULL,
    message_hi    TEXT         NOT NULL,
    fix_hint_en   TEXT,
    fix_hint_hi   TEXT,
    evidence_json JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT kyc_check_status_chk CHECK (status IN ('PASS', 'WARN', 'FAIL', 'SKIPPED')),
    CONSTRAINT kyc_check_severity_chk CHECK (severity IN ('BLOCKING', 'SOFT'))
);

CREATE INDEX idx_kyc_check_order ON kyc_check (order_id, id);
