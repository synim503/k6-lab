-- Профиль нагрузки, с которым запланирован запуск (null - нагрузка скрипта как сохранена на момент запуска)
alter table scheduled_run add column profile_id bigint;
alter table scheduled_run add column profile_name text;
