-- Campaign accounts (owner request 2026-10-29): every rupee spent on a campaign is recorded once, with its bill,
-- in one place, instead of paper registers kept by different staff members.
--   * vendors: the NGO's supplier list (shared by all campaigns), can be imported from CSV
--   * campaign_budget_lines: planned spend per category -> budget vs actual
--   * campaign_expenses: SUBMITTED -> APPROVED (voucher number) | REJECTED -> (edit) SUBMITTED; APPROVED -> VOID
--   * expense_attachments: bill photos / PDFs (max 5 per expense)
--   * staff_money_entries: immutable ledger of cash advances, staff-paid expenses, returns and reimbursements
-- Approved amounts and ledger rows never change (triggers); corrections are voids / reversing entries.

CREATE TABLE vendors (
    id          UUID PRIMARY KEY,
    ngo_id      UUID         NOT NULL,
    name        VARCHAR(120) NOT NULL,
    phone       VARCHAR(20),
    email       VARCHAR(120),
    gstin       VARCHAR(15),
    pan         VARCHAR(10),
    address     VARCHAR(300),
    category    VARCHAR(60),
    notes       VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_by  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_vendor_name ON vendors (ngo_id, lower(name));
CREATE INDEX ix_vendor_ngo ON vendors (ngo_id, active, name);

CREATE TABLE campaign_budget_lines (
    id          UUID PRIMARY KEY,
    campaign_id UUID          NOT NULL REFERENCES campaigns(id),
    ngo_id      UUID          NOT NULL,
    category    VARCHAR(60)   NOT NULL,
    planned     NUMERIC(14,2) NOT NULL CHECK (planned >= 0),
    position    INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_campaign_budget_category ON campaign_budget_lines (campaign_id, lower(category));

-- Voucher numbers per NGO and Indian financial year: EXP/2026-27/0001.
CREATE TABLE expense_voucher_counters (
    ngo_id      UUID        NOT NULL,
    fy          VARCHAR(7)  NOT NULL,
    last_no     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (ngo_id, fy)
);

CREATE TABLE campaign_expenses (
    id               UUID PRIMARY KEY,
    ngo_id           UUID          NOT NULL,
    campaign_id      UUID          NOT NULL REFERENCES campaigns(id),
    budget_line_id   UUID          REFERENCES campaign_budget_lines(id),
    category         VARCHAR(60)   NOT NULL,            -- snapshot of the budget line (or 'Other')
    description      VARCHAR(300)  NOT NULL,
    amount           NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    expense_date     DATE          NOT NULL,
    vendor_id        UUID          REFERENCES vendors(id),
    vendor_name      VARCHAR(120),                      -- snapshot (or a one-off payee without a vendor record)
    bill_number      VARCHAR(40),
    no_bill_reason   VARCHAR(200),                      -- required when there is no bill attachment
    payment_mode     VARCHAR(20)   NOT NULL CHECK (payment_mode IN ('CASH','UPI','BANK_TRANSFER','CHEQUE','CARD')),
    paid_by          VARCHAR(10)   NOT NULL CHECK (paid_by IN ('NGO','STAFF')),
    paid_by_user     UUID,                              -- the staff member who paid (advance or own pocket)
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('SUBMITTED','APPROVED','REJECTED','VOID')),
    voucher_no       VARCHAR(30),
    submitted_by     UUID          NOT NULL,
    submitted_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by       UUID,
    decided_at       TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    self_approved    BOOLEAN       NOT NULL DEFAULT false,
    voided_by        UUID,
    voided_at        TIMESTAMPTZ,
    void_reason      VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_expense_paid_by CHECK ((paid_by = 'STAFF') = (paid_by_user IS NOT NULL)),
    CONSTRAINT ck_expense_voucher CHECK ((status IN ('APPROVED','VOID')) = (voucher_no IS NOT NULL))
);
CREATE INDEX ix_expense_campaign ON campaign_expenses (campaign_id, status, expense_date DESC);
CREATE INDEX ix_expense_ngo_status ON campaign_expenses (ngo_id, status, submitted_at DESC);
CREATE UNIQUE INDEX uq_expense_voucher ON campaign_expenses (ngo_id, voucher_no) WHERE voucher_no IS NOT NULL;

CREATE TABLE expense_attachments (
    id           UUID PRIMARY KEY,
    expense_id   UUID         NOT NULL REFERENCES campaign_expenses(id),
    object_key   VARCHAR(300) NOT NULL,
    content_type VARCHAR(60)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    file_name    VARCHAR(120),
    uploaded_by  UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_expense_attachments ON expense_attachments (expense_id, created_at);

-- Signed from the NGO's point of view: + means the NGO owes the staff member, - means the staff member holds NGO cash.
--   ADVANCE        -amount  NGO gives cash to staff
--   EXPENSE        +amount  an approved expense the staff member paid (from the advance or own pocket)
--   RETURN         +amount  staff returns unused cash
--   REIMBURSEMENT  -amount  NGO pays the staff member back
--   EXPENSE_VOID   -amount  reversal when an approved staff-paid expense is voided
CREATE TABLE staff_money_entries (
    id           UUID PRIMARY KEY,
    ngo_id       UUID          NOT NULL,
    user_id      UUID          NOT NULL,
    campaign_id  UUID          REFERENCES campaigns(id),
    kind         VARCHAR(15)   NOT NULL CHECK (kind IN ('ADVANCE','EXPENSE','RETURN','REIMBURSEMENT','EXPENSE_VOID')),
    amount       NUMERIC(14,2) NOT NULL CHECK (amount <> 0),
    expense_id   UUID          REFERENCES campaign_expenses(id),
    mode         VARCHAR(20)   CHECK (mode IN ('CASH','UPI','BANK_TRANSFER','CHEQUE','CARD')),
    entry_date   DATE          NOT NULL,
    note         VARCHAR(300),
    recorded_by  UUID          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_money_sign CHECK ((kind IN ('EXPENSE','RETURN')) = (amount > 0))
);
CREATE INDEX ix_money_user ON staff_money_entries (ngo_id, user_id, created_at);
CREATE INDEX ix_money_campaign ON staff_money_entries (campaign_id);
CREATE UNIQUE INDEX uq_money_expense ON staff_money_entries (expense_id, kind) WHERE expense_id IS NOT NULL;

-- Financial history is immutable.
CREATE FUNCTION staff_money_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'staff_money_entries are immutable; record a reversing entry instead';
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_staff_money_immutable BEFORE UPDATE OR DELETE ON staff_money_entries
    FOR EACH ROW EXECUTE FUNCTION staff_money_immutable();

CREATE FUNCTION campaign_expense_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('APPROVED','VOID') THEN
            RAISE EXCEPTION 'approved expenses cannot be deleted; void them';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'VOID' THEN
        RAISE EXCEPTION 'void expenses are final';
    END IF;
    IF OLD.status = 'APPROVED' THEN
        IF NEW.status NOT IN ('APPROVED','VOID')
           OR NEW.amount <> OLD.amount OR NEW.expense_date <> OLD.expense_date OR NEW.campaign_id <> OLD.campaign_id
           OR NEW.voucher_no IS DISTINCT FROM OLD.voucher_no OR NEW.paid_by <> OLD.paid_by
           OR NEW.paid_by_user IS DISTINCT FROM OLD.paid_by_user OR NEW.category <> OLD.category THEN
            RAISE EXCEPTION 'approved expenses are immutable; void and re-enter';
        END IF;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;
CREATE TRIGGER trg_campaign_expense_guard BEFORE UPDATE OR DELETE ON campaign_expenses
    FOR EACH ROW EXECUTE FUNCTION campaign_expense_guard();
