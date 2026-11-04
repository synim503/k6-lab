-- Число генераторов задаётся в скрипте и подставляется при запуске
alter table script add column generator_count int not null default 1;
