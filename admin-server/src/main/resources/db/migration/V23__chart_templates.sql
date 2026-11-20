-- Свои графики отчёта: имя и PromQL, привязаны к скрипту; попадают в отчёты всех его запусков
create table chart_template (
    id bigserial primary key,
    script_id bigint not null,
    name varchar(120) not null,
    promql text not null,
    unit varchar(32),
    position int not null default 0,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    created_by text,
    unique (script_id, name)
);
