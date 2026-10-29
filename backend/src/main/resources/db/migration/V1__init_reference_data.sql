-- Phase 1: reference data loaded from data/*.csv

CREATE TABLE product (
    product_id               VARCHAR(5)  PRIMARY KEY,
    category                 TEXT        NOT NULL,
    product_name             TEXT        NOT NULL,
    description              TEXT        NOT NULL,
    unit                     TEXT        NOT NULL,
    price_min_inr_per_month  INT         NOT NULL,
    price_max_inr_per_month  INT         NOT NULL,
    one_time_setup_inr       INT         NOT NULL,
    min_contract_months      INT         NOT NULL,
    available_city_tiers     INT[]       NOT NULL,
    min_employees            INT         NOT NULL,
    min_sites                INT         NOT NULL,
    requires_gstin           BOOLEAN     NOT NULL,
    requires_one_of          TEXT[]      NOT NULL DEFAULT '{}',
    typical_install_days     INT         NOT NULL,
    target_segments          TEXT[]      NOT NULL,
    CONSTRAINT product_price_range_chk CHECK (price_min_inr_per_month <= price_max_inr_per_month)
);

CREATE TABLE gstin_registry (
    gstin                        CHAR(15)  PRIMARY KEY,
    legal_name                   TEXT      NOT NULL,
    trade_name                   TEXT      NOT NULL,
    constitution                 TEXT      NOT NULL,
    principal_place_of_business  TEXT      NOT NULL,
    state                        TEXT      NOT NULL,
    registration_date            DATE      NOT NULL,
    status                       TEXT      NOT NULL,
    CONSTRAINT gstin_registry_status_chk CHECK (status IN ('Active', 'Cancelled', 'Suspended'))
);

CREATE INDEX idx_gstin_registry_legal_name_lower ON gstin_registry (lower(legal_name));
CREATE INDEX idx_gstin_registry_trade_name_lower ON gstin_registry (lower(trade_name));

CREATE TABLE udyam_registry (
    udyam_number          VARCHAR(19)  PRIMARY KEY,
    enterprise_name       TEXT         NOT NULL,
    enterprise_type       TEXT         NOT NULL,
    major_activity        TEXT         NOT NULL,
    nic_description       TEXT         NOT NULL,
    state                 TEXT         NOT NULL,
    city                  TEXT         NOT NULL,
    date_of_registration  DATE         NOT NULL
);

CREATE INDEX idx_udyam_registry_enterprise_name_lower ON udyam_registry (lower(enterprise_name));

CREATE TABLE smb_lead (
    lead_id                     VARCHAR(10)  PRIMARY KEY,
    created_date                DATE         NOT NULL,
    lead_source                 TEXT         NOT NULL,
    business_name               TEXT         NOT NULL,
    business_type               TEXT         NOT NULL,
    segment                     TEXT         NOT NULL,
    city                        TEXT         NOT NULL,
    city_tier                   INT          NOT NULL,
    state                       TEXT         NOT NULL,
    preferred_language          TEXT         NOT NULL,
    employee_count              INT          NOT NULL,
    num_sites                   INT          NOT NULL,
    annual_turnover_band        TEXT         NOT NULL,
    has_gstin                   BOOLEAN      NOT NULL,
    gstin                       CHAR(15),
    has_udyam                   BOOLEAN      NOT NULL,
    udyam_number                VARCHAR(19),
    current_internet            TEXT         NOT NULL,
    current_provider            TEXT         NOT NULL,
    monthly_telecom_spend_inr   INT          NOT NULL,
    products_of_interest        TEXT[]       NOT NULL,
    contact_name                TEXT         NOT NULL,
    contact_role                TEXT         NOT NULL,
    stage_reached               TEXT         NOT NULL,
    outcome                     TEXT         NOT NULL,
    drop_reason                 TEXT,
    contacted_date              DATE,
    needs_assessed_date         DATE,
    quote_accepted_date         DATE,
    kyc_complete_date           DATE,
    activated_date              DATE,
    days_lead_to_activation     INT,
    customer_follow_up_calls    INT          NOT NULL,
    bundle_products             TEXT,
    monthly_bundle_value_inr    INT,
    one_time_setup_inr          INT,
    contract_months             INT,
    churned_within_6_months     BOOLEAN,
    nps_score                   INT,
    acquisition_cost_inr        INT          NOT NULL,
    CONSTRAINT smb_lead_stage_chk CHECK (stage_reached IN
        ('Lead', 'Contacted', 'Needs assessed', 'Quote accepted', 'KYC complete', 'Activated')),
    CONSTRAINT smb_lead_outcome_chk CHECK (outcome IN ('Activated', 'Dropped'))
);

CREATE INDEX idx_smb_lead_segment ON smb_lead (segment);
CREATE INDEX idx_smb_lead_outcome ON smb_lead (outcome);
CREATE INDEX idx_smb_lead_gstin ON smb_lead (gstin);

CREATE TABLE lead_bundle_item (
    lead_id     VARCHAR(10)  NOT NULL REFERENCES smb_lead (lead_id),
    product_id  VARCHAR(5)   NOT NULL REFERENCES product (product_id),
    quantity    INT          NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (lead_id, product_id)
);
