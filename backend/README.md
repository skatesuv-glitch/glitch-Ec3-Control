# C3-Control Connected Car backend

Backend boundary for the authorized Stellantis Connected Car integration.

## Security model

The Android APK must never contain the Stellantis client secret, mTLS private key, or client certificate. Those credentials belong only in the backend secret store/runtime environment.

The mobile app talks only to this backend. The backend performs OAuth token exchange/refresh and mTLS calls to the authorized Connected Car API after Stellantis/Mobilisights provisions the application.

## Initial read-only scope

C3-Control requests telemetry only. Target data: battery state of charge, electric range, charging state, odometer and source timestamps when available for the enrolled vehicle.

No remote vehicle commands are part of this backend.

## Runtime variables (placeholders only)

- STELLANTIS_CLIENT_ID
- STELLANTIS_CLIENT_SECRET
- STELLANTIS_MZP_ID
- STELLANTIS_MTLS_CERT_PATH
- STELLANTIS_MTLS_KEY_PATH
- STELLANTIS_CA_CERT_PATH
- STELLANTIS_REALM

Never commit the values or certificate/key files.

## Planned API boundary

- GET /health: backend readiness only, no secrets.
- GET /auth/start: begins authorized user enrollment.
- GET /auth/callback: server-side authorization-code exchange.
- GET /vehicle: safe vehicle identity/status envelope.
- GET /vehicle/telemetry: normalized read-only telemetry for the Android client.

Until authorized credentials are provisioned, Connected Car must report a pending/not-configured state rather than falling back to RemoteServices activation.
