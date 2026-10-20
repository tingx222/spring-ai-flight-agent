-- Existing orders are retained and remain unassigned until an administrator assigns them.
CREATE TABLE IF NOT EXISTS flight_bookings (
    booking_number VARCHAR(32) PRIMARY KEY, customer_name VARCHAR(100) NOT NULL,
    flight_date DATE NOT NULL, booking_status VARCHAR(32) NOT NULL,
    departure VARCHAR(100) NOT NULL, destination VARCHAR(100) NOT NULL, booking_class VARCHAR(32) NOT NULL
);
CREATE TABLE IF NOT EXISTS booking_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, booking_number VARCHAR(255), action VARCHAR(16) NOT NULL,
    result VARCHAR(16) NOT NULL, message VARCHAR(1000) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
    before_data TEXT, after_data TEXT
);
CREATE TABLE app_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(40) NOT NULL,
    role VARCHAR(16) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE booking_ownership (
    booking_number VARCHAR(32) PRIMARY KEY, user_id BIGINT NOT NULL,
    FOREIGN KEY (booking_number) REFERENCES flight_bookings(booking_number),
    FOREIGN KEY (user_id) REFERENCES app_users(id)
);
CREATE INDEX idx_booking_owner ON booking_ownership(user_id);
CREATE TABLE ownership_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, booking_number VARCHAR(32) NOT NULL,
    actor_user_id BIGINT NOT NULL, previous_user_id BIGINT, new_user_id BIGINT NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
ALTER TABLE booking_audit ADD COLUMN actor_user_id BIGINT;
CREATE INDEX idx_booking_audit_number ON booking_audit(booking_number, id);
