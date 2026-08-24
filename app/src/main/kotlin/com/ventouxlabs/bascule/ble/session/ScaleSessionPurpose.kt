package com.ventouxlabs.bascule.ble.session

/** Why a process owns the BF720 connection. Only registration may allocate a scale slot. */
enum class ScaleSessionPurpose(val permitsRegistration: Boolean) {
    EXISTING_USER_CONSENT(false),
    REGISTER_NEW(true),
    MEASUREMENT(false),
    ADMINISTRATION(false),
}
