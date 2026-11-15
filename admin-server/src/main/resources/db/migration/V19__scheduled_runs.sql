-- Запланированные запуски: в назначенное время сервер сам создаёт запуск с заданными переменными и интенсивностью по операциям
create table scheduled_run (
    id bigserial primary key,
    script_id bigint not null,
    script_name text not null,
    run_at timestamptz not null,
    created_at timestamptz not null default now(),
    created_by text,
    generator_ids jsonb not null default '[]',
    generator_count integer,
    -- null - переменные как в скрипте на момент запуска; иначе полный набор переменных этого запуска
    env jsonb,
    intensity jsonb not null default '{}',
    status varchar(16) not null default 'PENDING',
    run_id bigint,
    error text,
    finished_at timestamptz
);
create index scheduled_run_due on scheduled_run (status, run_at);
