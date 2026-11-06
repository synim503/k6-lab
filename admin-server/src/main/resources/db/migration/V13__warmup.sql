-- Прогрев: перед основной нагрузкой каждый сценарий отрабатывает прогрев заданной длительности на доле нагрузки;
-- ошибки и время ответа прогрева не попадают в SLA, пороги k6 и итоги. 0 секунд - без прогрева.
alter table script add column warmup_sec int not null default 0;
alter table script add column warmup_pct int not null default 30;
