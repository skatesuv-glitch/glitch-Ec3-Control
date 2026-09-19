# Stellantis connection strategy

## Status — September 2026

The current community integration for PSA/Stellantis vehicles reports that Stellantis does not provide public B2C API credentials for this use case. It uses the mobile-app authentication flow instead.

EC3-Control therefore keeps authentication behind an interface so the app can migrate if Stellantis changes the flow or exposes an official consumer route later.

## Rules

1. No Citroën/Stellantis password, PIN, VIN, client secret, access token or refresh token in Git.
2. Tokens must be stored using Android platform-backed secure storage.
3. A command being accepted by the backend is not the same as the vehicle confirming execution.
4. Climate and charging commands require the relevant vehicle remote service (E-Remote / Connect Plus).
5. Avoid aggressive wake-up/polling. Vehicle connectivity may sleep to protect the 12 V battery.
6. Show the timestamp/source of vehicle data in the UI.
7. The unofficial/mobile-app-compatible adapter must remain isolated from UI and domain models.

## Planned flow

Login -> OAuth callback -> token exchange -> encrypted session -> vehicle discovery -> status mapping.

Remote command:

UI request -> Sending -> backend Accepted -> vehicle Confirmed / Failed / TimedOut.

## Reference implementation

Community reference: andreadegiovine/homeassistant-stellantis-vehicles (develop branch).
It supports status, wake-up, preconditioning and EV charging commands on compatible PSA vehicles/services.
