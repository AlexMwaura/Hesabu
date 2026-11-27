-- Hesabu cloud database (Postgres). Money in cents, quantities in thousandths.

CREATE TABLE business (
    id             UUID PRIMARY KEY,
    name           TEXT        NOT NULL,
    kra_pin        TEXT,
    owner_key_hash TEXT        NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE branch (
    id               UUID PRIMARY KEY,
    business_id      UUID        NOT NULL REFERENCES business (id),
    name             TEXT        NOT NULL,
    code             TEXT        NOT NULL,
    bhf_id           TEXT        NOT NULL DEFAULT '00',
    hub_key_hash     TEXT        NOT NULL UNIQUE,
    -- Till or paybill customers pay by typing a code (C2B).
    mpesa_short_code TEXT,
    -- Short code and passkey for M-Pesa prompts (STK Push).
    -- TODO(secrets): encrypt the passkey at rest before production.
    stk_short_code   TEXT,
    stk_passkey      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, code)
);

CREATE SEQUENCE product_version_seq;
CREATE SEQUENCE item_serial_seq;

CREATE TABLE product (
    business_id         UUID        NOT NULL REFERENCES business (id),
    code                TEXT        NOT NULL,
    item_class_code     TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    barcode             TEXT,
    price_cents         BIGINT      NOT NULL CHECK (price_cents >= 0),
    tax_type            CHAR(1)     NOT NULL,
    is_service          BOOLEAN     NOT NULL DEFAULT false,
    qty_unit_code       TEXT        NOT NULL DEFAULT 'U',
    pkg_unit_code       TEXT        NOT NULL DEFAULT 'NT',
    reorder_level_milli BIGINT      NOT NULL DEFAULT 0,
    active              BOOLEAN     NOT NULL DEFAULT true,
    version             BIGINT      NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (business_id, code)
);
CREATE INDEX product_version ON product (business_id, version);

-- Every event a hub sends, kept as received. The id makes ingest idempotent.
CREATE TABLE hub_event (
    id          UUID PRIMARY KEY,
    branch_id   UUID        NOT NULL REFERENCES branch (id),
    type        TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload     JSONB       NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hub_event_branch ON hub_event (branch_id, received_at);

CREATE TABLE sale (
    id               UUID PRIMARY KEY,
    branch_id        UUID        NOT NULL REFERENCES branch (id),
    receipt_type     CHAR(1)     NOT NULL,
    original_sale_id UUID,
    trade_invoice_no TEXT        NOT NULL,
    invc_no          BIGINT      NOT NULL,
    shift_id         UUID,
    till_id          TEXT,
    cashier_name     TEXT,
    customer_id      UUID,
    total_cents      BIGINT      NOT NULL,
    tax_cents        BIGINT      NOT NULL,
    signed_at        TIMESTAMPTZ NOT NULL,
    sdc_id           TEXT,
    scu_rcpt_no      BIGINT
);
CREATE INDEX sale_branch_signed ON sale (branch_id, signed_at);

CREATE TABLE sale_line (
    sale_id          UUID   NOT NULL REFERENCES sale (id),
    seq              INT    NOT NULL,
    product_code     TEXT   NOT NULL,
    name             TEXT   NOT NULL,
    qty_milli        BIGINT NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    total_cents      BIGINT NOT NULL,
    tax_type         CHAR(1) NOT NULL,
    PRIMARY KEY (sale_id, seq)
);

CREATE TABLE sale_tender (
    sale_id      UUID   NOT NULL REFERENCES sale (id),
    seq          INT    NOT NULL,
    method       TEXT   NOT NULL,
    amount_cents BIGINT NOT NULL,
    mpesa_code   TEXT,
    PRIMARY KEY (sale_id, seq)
);

CREATE TABLE stock_level (
    branch_id           UUID        NOT NULL REFERENCES branch (id),
    product_code        TEXT        NOT NULL,
    on_hand_milli       BIGINT      NOT NULL,
    reorder_level_milli BIGINT      NOT NULL,
    as_of               TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (branch_id, product_code)
);

CREATE TABLE shift_close (
    shift_id            UUID PRIMARY KEY,
    branch_id           UUID        NOT NULL REFERENCES branch (id),
    till_id             TEXT        NOT NULL,
    cashier_name        TEXT        NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    closed_at           TIMESTAMPTZ NOT NULL,
    float_cents         BIGINT      NOT NULL,
    expected_cash_cents BIGINT      NOT NULL,
    counted_cash_cents  BIGINT      NOT NULL,
    variance_cents      BIGINT      NOT NULL,
    sales_count         INT         NOT NULL,
    sales_total_cents   BIGINT      NOT NULL
);

CREATE TABLE customer (
    id                 UUID PRIMARY KEY,
    branch_id          UUID        NOT NULL REFERENCES branch (id),
    name               TEXT        NOT NULL,
    phone              TEXT,
    credit_limit_cents BIGINT      NOT NULL,
    balance_cents      BIGINT      NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

-- Safaricom's confirmations. TransID is unique, so retried callbacks are stored once.
CREATE TABLE mpesa_confirmation (
    trans_id     TEXT PRIMARY KEY,
    short_code   TEXT        NOT NULL,
    amount_cents BIGINT      NOT NULL,
    source       TEXT        NOT NULL,
    msisdn       TEXT,
    bill_ref     TEXT,
    trans_time   TEXT,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Codes hubs attached to sales or deni repayments. One code, one claim.
CREATE TABLE mpesa_claim (
    code                   TEXT PRIMARY KEY,
    branch_id              UUID        NOT NULL REFERENCES branch (id),
    ref                    TEXT        NOT NULL,
    expected_amount_cents  BIGINT      NOT NULL,
    status                 TEXT        NOT NULL,
    confirmed_amount_cents BIGINT,
    claimed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mpesa_claim_branch ON mpesa_claim (branch_id, claimed_at);

CREATE TABLE stk_request (
    checkout_request_id TEXT PRIMARY KEY,
    branch_id           UUID        NOT NULL REFERENCES branch (id),
    ref                 TEXT        NOT NULL,
    phone               TEXT        NOT NULL,
    amount_cents        BIGINT      NOT NULL,
    short_code          TEXT        NOT NULL,
    state               TEXT        NOT NULL,
    mpesa_code          TEXT,
    result_code         INT,
    result_desc         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
