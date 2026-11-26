-- The W3C traceparent of the request that appended the outbox row (spec §13). The publisher sends
-- it as the Kafka record's traceparent header, so one trace covers HTTP → posting → outbox → Kafka
-- → projector. Null for rows appended outside a trace, and for every row appended before this.
alter table outbox add column traceparent text;
