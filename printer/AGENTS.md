# Repository Guidelines

## Module Scope

Follow the root `AGENTS.md` in addition to this guide. `printer` owns device configuration,
persistence, provider discovery, and network transports such as Moonraker, FTP, and Bambu
integration. Public device contracts remain in `api-printer`. Keep physical printer devices
distinct from slicer printer presets.

## Device Safety

Never log access codes, tokens, passwords, or complete authenticated URLs, and never store them in
project documents. Validate addresses and capabilities before opening sessions. Do not
automatically retry ambiguous state-changing requests such as starting or cancelling a print;
report uncertainty so the caller can reconcile device state. Make session ownership and cleanup
explicit.

## Verification

Use local protocol fixtures and deterministic fakes for unit tests; tests must not contact real
devices by default. Run `./gradlew :printer-impl:test` for module changes. Record manual hardware
qualification separately, including printer model, firmware, transport, and the operation tested.
