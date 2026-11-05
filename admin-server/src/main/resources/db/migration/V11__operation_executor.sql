-- Операция = сценарий k6: исполнитель (executor) и произвольные параметры сценария (JSON как в options.scenarios).
-- ramping-arrival-rate - прежнее поведение: интенсивность и ступени профиля; остальные исполнители работают по своим параметрам.
alter table load_operation add column executor varchar(32) not null default 'ramping-arrival-rate';
alter table load_operation add column scenario jsonb not null default '{}'::jsonb;
