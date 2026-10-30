-- One alert per REVIEW/BLOCK transaction. card_id, risk_score and decision are copied from the transaction
-- so the alert list needs no join.
CREATE TABLE alerts (
    id             UUID                     NOT NULL PRIMARY KEY,
    transaction_id UUID                     NOT NULL UNIQUE REFERENCES transactions (id),
    card_id        VARCHAR(64)              NOT NULL,
    risk_score     INT                      NOT NULL,
    decision       VARCHAR(16)              NOT NULL,
    status         VARCHAR(16)              NOT NULL,
    note           VARCHAR(500),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at    TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_alerts_status_created ON alerts (status, created_at DESC);
