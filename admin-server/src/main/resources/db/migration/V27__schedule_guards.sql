-- Автопроверки планировщика: проверка метрики Prometheus после прогона / перед стартом следующего теста
-- и автоматическое выполнение SSH-скрипта при срабатывании порога (например, очистка диска).
create table schedule_guard (
    id                bigserial primary key,
    name              varchar(120) not null,
    enabled           boolean not null default true,
    promql            text not null,
    cmp               varchar(8) not null default 'GT',
    threshold         double precision not null,
    unit              varchar(32) not null default '',
    run_when          varchar(16) not null default 'BOTH',
    ssh_host          varchar(255) not null,
    ssh_port          int not null default 22,
    ssh_credential_id bigint not null references ssh_credential(id),
    use_sudo          boolean not null default false,
    script            text not null,
    timeout_sec       int not null default 120,
    cooldown_sec      int not null default 60,
    block_on_fail     boolean not null default false,
    last_checked_at   timestamptz,
    last_value        double precision,
    last_series       text,
    last_triggered_at timestamptz,
    last_status       varchar(24),
    last_output       text,
    created_by        varchar(100),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index idx_schedule_guard_enabled on schedule_guard(enabled, id);
