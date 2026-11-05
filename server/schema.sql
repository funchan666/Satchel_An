CREATE TABLE IF NOT EXISTS play_orders (
    id UUID PRIMARY KEY,
    owner TEXT NOT NULL,
    request_id TEXT NOT NULL CHECK (length(request_id) BETWEEN 1 AND 128),
    product_id TEXT NOT NULL,
    action TEXT NOT NULL CHECK (action IN ('pay', 'subscription.purchase')),
    account_id TEXT NOT NULL,
    profile_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner, request_id),
    UNIQUE (profile_id)
);
CREATE TABLE IF NOT EXISTS play_subscriptions (
    token_hash CHAR(64) PRIMARY KEY,
    token_ciphertext TEXT NOT NULL,
    owner TEXT NOT NULL,
    order_id UUID NOT NULL UNIQUE REFERENCES play_orders(id),
    product_id TEXT NOT NULL,
    state TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    ack_state TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS play_subscriptions_owner_idx ON play_subscriptions(owner);
