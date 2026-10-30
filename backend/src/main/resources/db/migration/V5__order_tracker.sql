-- Phase 8: order tracker. Who caused each event, the business date it counts for, the install slot, holds, and
-- how many times the customer checked the tracker (self-serve status checks instead of calling support).

ALTER TABLE order_event ADD COLUMN actor VARCHAR(10) NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE order_event ADD CONSTRAINT order_event_actor_chk CHECK (actor IN ('SYSTEM', 'CUSTOMER', 'DEMO'));

-- created_at stays the real time; business_date drives ETAs ("today" = app.reference-date in the prototype).
-- Existing rows were created under the same reference date; every new row sets it explicitly.
ALTER TABLE order_event ADD COLUMN business_date DATE NOT NULL DEFAULT DATE '2026-07-15';
ALTER TABLE order_event ALTER COLUMN business_date DROP DEFAULT;

ALTER TABLE customer_order ADD COLUMN tracker_view_count INT NOT NULL DEFAULT 0;
ALTER TABLE customer_order ADD COLUMN install_slot_date DATE;
ALTER TABLE customer_order ADD COLUMN install_slot VARCHAR(10);
ALTER TABLE customer_order ADD CONSTRAINT customer_order_install_slot_chk
    CHECK (install_slot IS NULL OR install_slot IN ('MORNING', 'AFTERNOON', 'EVENING'));
ALTER TABLE customer_order ADD COLUMN hold_reason VARCHAR(30);
ALTER TABLE customer_order ADD COLUMN hold_note TEXT;
ALTER TABLE customer_order ADD COLUMN hold_previous_status VARCHAR(40);
