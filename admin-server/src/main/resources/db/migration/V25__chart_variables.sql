-- Переменные своих графиков отчёта (в запросах пишутся с долларом перед именем): значение задаётся на скрипт;
-- значение run_id в фигурных скобках - идентификатор запуска, range - окно rate/increase
create table chart_variable (
    id bigserial primary key,
    script_id bigint not null,
    name varchar(64) not null,
    value text not null default '',
    unique (script_id, name)
);
