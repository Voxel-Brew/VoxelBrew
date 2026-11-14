# Printer implementation

- Own device persistence, network sessions and printer providers.
- Keep public contracts in api/printer and transport dependencies here. Expose
  services through the public printer runtime API; physical devices are distinct
  from slicer printer presets.
- Never log credentials or embed them in project documents.
- Never retry ambiguous print-start requests automatically.
- Tests use local protocol fixtures; physical qualification requires real hardware.
