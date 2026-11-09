# costguard-common

What every Python service shares:

- `wire`: the Schema Registry wire format (byte 0, 4-byte schema id, Avro binary), the same bytes the Java side
  writes. `tests/test_wire.py` checks against bytes produced by the Java encoder.
- `schemas`: loads `schemas/avro/*.avsc`, the single source of the Kafka contracts.
- `registry`: a small Schema Registry client with caching.
- `focus`: the FOCUS cost record that `cost.records.v1` carries.
- `settings`: environment settings with the names of `.env.example`.
- `logs`: `configure()` instead of `logging.basicConfig`; masks e-mails, full names, phones, cards and tokens in
  every log line (ADR 0023), the same rules as `LogMasking` of the Java services.
