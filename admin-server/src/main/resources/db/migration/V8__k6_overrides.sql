-- Ручные правки итогового конфига k6 (JSON merge patch поверх расчётного конфига): maxVUs, preAllocatedVUs, gracefulStop и т.п.
alter table script add column k6_overrides jsonb not null default '{}'::jsonb;
