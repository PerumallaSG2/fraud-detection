-- A plain-English explanation per REVIEW/BLOCK transaction, written by its own consumer of the decisions topic.
-- Keyed by transaction, not alert: the alert and explanation consumers are not ordered relative to each other,
-- but the transaction row always exists first. The API returns it inside the transaction's alert.
CREATE TABLE alert_explanations (
    transaction_id UUID                     NOT NULL PRIMARY KEY REFERENCES transactions (id),
    text           VARCHAR(500)             NOT NULL,
    source         VARCHAR(16)              NOT NULL,
    model          VARCHAR(100),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
