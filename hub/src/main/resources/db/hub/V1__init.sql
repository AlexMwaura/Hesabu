-- Hub database (SQLite). Money in cents, quantities in thousandths, times as ISO-8601 text.

CREATE TABLE product (
    code                TEXT PRIMARY KEY,
    item_class_code     TEXT    NOT NULL,
    name                TEXT    NOT NULL,
    barcode             TEXT,
    price_cents         INTEGER NOT NULL,
    tax_type            TEXT    NOT NULL,
    is_service          INTEGER NOT NULL DEFAULT 0,
    qty_unit_code       TEXT    NOT NULL,
    pkg_unit_code       TEXT    NOT NULL,
    reorder_level_milli INTEGER NOT NULL DEFAULT 0,
    active              INTEGER NOT NULL DEFAULT 1,
    version             INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX product_barcode ON product (barcode);

-- TIS spec 6.29: goods may not be sold beyond stock on hand, so stock never goes negative.
CREATE TABLE stock (
    product_code  TEXT PRIMARY KEY,
    on_hand_milli INTEGER NOT NULL DEFAULT 0 CHECK (on_hand_milli >= 0)
);

CREATE TABLE stock_movement (
    id              TEXT PRIMARY KEY,
    product_code    TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    kra_code        TEXT    NOT NULL,
    qty_milli       INTEGER NOT NULL,
    unit_cost_cents INTEGER,
    ref             TEXT,
    note            TEXT,
    at              TEXT    NOT NULL
);
CREATE INDEX stock_movement_product ON stock_movement (product_code, at);

-- TIS spec 6.3: one ascending invoice series; restored from the VSCU at start-up.
CREATE TABLE fiscal_counter (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    last_invc_no INTEGER NOT NULL
);
INSERT INTO fiscal_counter (id, last_invc_no) VALUES (1, 0);

CREATE TABLE shift (
    id                  TEXT PRIMARY KEY,
    till_id             TEXT    NOT NULL,
    cashier_id          TEXT    NOT NULL,
    cashier_name        TEXT    NOT NULL,
    float_cents         INTEGER NOT NULL,
    opened_at           TEXT    NOT NULL,
    closed_at           TEXT,
    expected_cash_cents INTEGER,
    counted_cash_cents  INTEGER
);
CREATE UNIQUE INDEX shift_one_open_per_till ON shift (till_id) WHERE closed_at IS NULL;

CREATE TABLE customer (
    id                 TEXT PRIMARY KEY,
    name               TEXT    NOT NULL,
    phone              TEXT,
    kra_pin            TEXT,
    credit_limit_cents INTEGER NOT NULL DEFAULT 0,
    balance_cents      INTEGER NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
    created_at         TEXT    NOT NULL
);

-- Deni book: CHARGE (credit sale), PAYMENT (customer pays), REFUND (credit note against deni).
CREATE TABLE deni_entry (
    id           TEXT PRIMARY KEY,
    customer_id  TEXT    NOT NULL REFERENCES customer (id),
    kind         TEXT    NOT NULL,
    amount_cents INTEGER NOT NULL,
    method       TEXT,
    mpesa_code   TEXT,
    ref          TEXT,
    shift_id     TEXT,
    at           TEXT    NOT NULL
);
CREATE INDEX deni_entry_customer ON deni_entry (customer_id, at);

-- Sales and credit notes. A row is written before the VSCU is asked to sign (status SIGNING) so a
-- crash or a lost answer can never leave a signed receipt the hub knows nothing about.
CREATE TABLE sale (
    id                 TEXT PRIMARY KEY,
    client_ref         TEXT    NOT NULL UNIQUE,
    receipt_type       TEXT    NOT NULL,
    original_sale_id   TEXT,
    shift_id           TEXT    NOT NULL,
    till_id            TEXT    NOT NULL,
    status             TEXT    NOT NULL,
    attempt_invc_no    INTEGER,
    invc_no            INTEGER UNIQUE,
    trade_invoice_no   TEXT,
    total_cents        INTEGER NOT NULL,
    tax_cents          INTEGER NOT NULL,
    customer_id        TEXT,
    credit_note_reason TEXT,
    created_at         TEXT    NOT NULL,
    signed_at          TEXT,
    vscu_result_cd     TEXT,
    vscu_result_msg    TEXT,
    request_json       TEXT,
    signature_json     TEXT
);
CREATE INDEX sale_shift ON sale (shift_id);
CREATE INDEX sale_original ON sale (original_sale_id);

CREATE TABLE sale_line (
    sale_id          TEXT    NOT NULL,
    seq              INTEGER NOT NULL,
    product_code     TEXT    NOT NULL,
    name             TEXT    NOT NULL,
    qty_milli        INTEGER NOT NULL,
    unit_price_cents INTEGER NOT NULL,
    total_cents      INTEGER NOT NULL,
    tax_type         TEXT    NOT NULL,
    is_service       INTEGER NOT NULL,
    PRIMARY KEY (sale_id, seq)
);

CREATE TABLE tender (
    sale_id             TEXT    NOT NULL,
    seq                 INTEGER NOT NULL,
    method              TEXT    NOT NULL,
    amount_cents        INTEGER NOT NULL,
    mpesa_code          TEXT,
    checkout_request_id TEXT,
    PRIMARY KEY (sale_id, seq)
);

-- One row per M-Pesa code this branch has accepted. The primary key stops one customer's message
-- being used for two sales, even while the hub is offline.
CREATE TABLE mpesa_claim (
    code                   TEXT PRIMARY KEY,
    ref                    TEXT    NOT NULL,
    amount_cents           INTEGER NOT NULL,
    status                 TEXT    NOT NULL,
    submitted              INTEGER NOT NULL DEFAULT 0,
    confirmed_amount_cents INTEGER,
    updated_at             TEXT    NOT NULL
);
CREATE INDEX mpesa_claim_ref ON mpesa_claim (ref);

-- Transactional outbox: events written in the same transaction as the change, sent to the cloud later.
CREATE TABLE outbox (
    seq        INTEGER PRIMARY KEY AUTOINCREMENT,
    id         TEXT    NOT NULL UNIQUE,
    type       TEXT    NOT NULL,
    payload    TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    sent_at    TEXT,
    attempts   INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX outbox_unsent ON outbox (sent_at, seq);

CREATE TABLE sync_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
