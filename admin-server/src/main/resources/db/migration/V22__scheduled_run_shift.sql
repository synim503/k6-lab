-- Запись расписания может сдвигаться вперёд, если генераторы освободились раньше (ранняя остановка предыдущего запуска)
alter table scheduled_run add column shift_forward boolean not null default false;
