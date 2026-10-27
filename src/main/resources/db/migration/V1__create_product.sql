CREATE TABLE product (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(255)   NOT NULL,
    price   NUMERIC(12, 2) NOT NULL,
    stock   INTEGER        NOT NULL CHECK (stock >= 0),
    version BIGINT         NOT NULL DEFAULT 0
);

INSERT INTO product (name, price, stock) VALUES
    ('Keyboard', 49.99, 10),
    ('Mouse',    19.99, 10),
    ('Monitor', 199.99, 10);
