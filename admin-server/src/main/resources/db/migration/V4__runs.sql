-- Этапы 4-5: запуски, генераторы run, ступени, логи (партиционированы по дням).

create table run (
    id                  bigserial primary key,
    scenario_id         bigint references scenario (id) on delete set null,
    scenario_name       varchar(150) not null,
    status              varchar(20)  not null check (status in ('QUEUED', 'PREPARING', 'RUNNING', 'STOPPING', 'FINISHED', 'FAILED', 'ABORTED')),
    degraded            boolean      not null default false,
    thresholds_failed   boolean      not null default false,
    failure_policy      varchar(20)  not null default 'CONTINUE' check (failure_policy in ('CONTINUE', 'STOP_ALL')),
    stop_reason         text,
    error               text,
    request             jsonb        not null default '{}',
    snapshot            jsonb        not null default '{}',
    k6_config           jsonb,
    script_version_id   bigint references script_version (id) on delete set null,
    prepare_timeout_sec integer      not null default 120,
    queued_at           timestamptz  not null default now(),
    planned_start_at    timestamptz,
    started_at          timestamptz,
    finished_at         timestamptz,
    conclusion          text,
    conclusion_comment  text,
    is_baseline         boolean      not null default false,
    created_by          varchar(100),
    lock_version        bigint       not null default 0,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);
create index run_scenario_idx on run (scenario_id, queued_at desc);
create index run_status_idx on run (status);

create table run_generator (
    id                   bigserial primary key,
    run_id               bigint       not null references run (id) on delete cascade,
    generator_id         bigint references generator (id) on delete set null,
    generator_name       varchar(100) not null,
    seq_index            integer      not null,
    weight               integer      not null default 1,
    segment_start        varchar(30)  not null default '0',
    segment_end          varchar(30)  not null default '1',
    status               varchar(20)  not null default 'RESERVED',
    exit_code            integer,
    k6_api_port          integer,
    summary              jsonb,
    offline_intervals    jsonb        not null default '[]',
    last_vus             bigint,
    last_iterations      bigint,
    last_dropped         bigint,
    dropped_log_lines    bigint       not null default 0,
    prepare_error        text,
    artifact_token_hash  varchar(64),
    artifact_consumed_at timestamptz,
    unique (run_id, seq_index)
);
create index run_generator_run_idx on run_generator (run_id);
create index run_generator_gen_idx on run_generator (generator_id);

create table run_step (
    id             bigserial primary key,
    run_id         bigint       not null references run (id) on delete cascade,
    step_no        integer      not null,
    label          varchar(200),
    level_pct      double precision not null,
    ramp_start_at  timestamptz  not null,
    hold_start_at  timestamptz  not null,
    hold_end_at    timestamptz  not null,
    reliable       boolean      not null default true,
    unique (run_id, step_no)
);

-- Логи run: поток до нескольких тысяч строк/с на все генераторы, retention 30 дней.
-- Партиции по дням: очистка = DROP PARTITION (мгновенно), фильтры и поиск - обычным SQL.
create table run_log (
    id           bigserial,
    run_id       bigint       not null,
    generator_id bigint,
    ts           timestamptz  not null,
    level        varchar(10)  not null default 'INFO',
    line         text         not null
) partition by range (ts);
create table run_log_default partition of run_log default;
create index run_log_run_ts_idx on run_log (run_id, ts, id);

-- стартовые партиции: вчера .. +14 дней (дальше создаёт планировщик)
do $$
declare d date;
begin
    for d in select generate_series(current_date - 1, current_date + 14, interval '1 day')::date loop
        execute format('create table if not exists %I partition of run_log for values from (%L) to (%L)',
                       'run_log_' || to_char(d, 'YYYYMMDD'), d, d + 1);
    end loop;
end $$;
