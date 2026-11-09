-- Сколько секунд k6 инициализировал скрипт до реального начала нагрузки (по данным Prometheus); null - ещё не определено
alter table run add column init_sec integer;
