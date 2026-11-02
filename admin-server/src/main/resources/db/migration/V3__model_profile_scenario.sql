-- Этап 3: модель нагрузки, профили, сценарии, секреты, SUT, режимы тестовых данных.

create table load_model (
    id               bigserial primary key,
    name             varchar(150) not null unique,
    description      varchar(1000),
    script_id        bigint       not null references script (id),
    is_template      boolean      not null default false,
    -- общая интенсивность модели (производная от операций), оп/сек
    total_per_sec    double precision not null default 0,
    lock_version     bigint       not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

create table load_operation (
    id                    bigserial primary key,
    model_id              bigint       not null references load_model (id) on delete cascade,
    position              integer      not null,
    name                  varchar(64)  not null,
    exec_fn               varchar(200) not null,
    mode                  varchar(10)  not null check (mode in ('OPEN', 'CLOSED')),
    intensity_value       double precision not null default 0,
    intensity_unit        varchar(10)  not null default 'PER_HOUR' check (intensity_unit in ('PER_SEC', 'PER_MIN', 'PER_HOUR')),
    share_pct             double precision not null default 0,
    expected_duration_sec double precision not null default 0,
    pacing_ms             integer,
    vus_override          integer,
    pre_alloc_override    integer,
    max_vus_override      integer,
    sla_percentile        integer      not null default 95 check (sla_percentile in (90, 95, 99)),
    sla_threshold_ms      double precision not null default 1000,
    max_error_pct         double precision not null default 1,
    failure_source        varchar(10)  not null default 'HTTP' check (failure_source in ('HTTP', 'CHECKS')),
    env                   jsonb        not null default '{}',
    tags                  jsonb        not null default '{}',
    enabled               boolean      not null default true,
    fixed_level_pct       double precision,
    unique (model_id, name)
);

create table profile_template (
    id            bigserial primary key,
    name          varchar(150) not null unique,
    description   varchar(1000),
    definition    jsonb        not null,
    lock_version  bigint       not null default 0,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

create table test_profile (
    id                      bigserial primary key,
    name                    varchar(150) not null unique,
    type                    varchar(20)  not null check (type in ('MAX_SEARCH', 'CONFIRMATION', 'RELIABILITY', 'STRESS', 'SPIKE', 'CUSTOM', 'TEMPLATE')),
    template_id             bigint references profile_template (id),
    params                  jsonb        not null default '{}',
    graceful_stop_sec       integer      not null default 30,
    graceful_ramp_down_sec  integer      not null default 30,
    is_template             boolean      not null default false,
    lock_version            bigint       not null default 0,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now()
);

create table secret (
    id            bigserial primary key,
    name          varchar(100) not null unique,
    description   varchar(500),
    value_enc     bytea        not null,
    lock_version  bigint       not null default 0,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

create table sut (
    id                  bigserial primary key,
    name                varchar(150) not null unique,
    description         varchar(1000),
    grafana_url_tpl     varchar(1000),
    lock_version        bigint       not null default 0,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);

create table sut_metric (
    id               bigserial primary key,
    sut_id           bigint       not null references sut (id) on delete cascade,
    position         integer      not null,
    name             varchar(150) not null,
    promql           text         not null,
    unit             varchar(30),
    aggregation      varchar(10)  not null default 'AVG' check (aggregation in ('AVG', 'MAX', 'LAST')),
    warn_threshold   double precision,
    warn_direction   varchar(10)  not null default 'ABOVE' check (warn_direction in ('ABOVE', 'BELOW'))
);

create table scenario (
    id                  bigserial primary key,
    name                varchar(150) not null unique,
    description         varchar(1000),
    script_id           bigint       not null references script (id),
    script_version_id   bigint references script_version (id),
    follow_branch       boolean      not null default false,
    model_id            bigint       not null references load_model (id),
    profile_id          bigint       not null references test_profile (id),
    env                 jsonb        not null default '{}',
    expert_mode         boolean      not null default false,
    expert_config       jsonb,
    stop_criteria       jsonb        not null default '{}',
    success_criteria    jsonb        not null default '{}',
    sut_id              bigint references sut (id),
    is_template         boolean      not null default false,
    lock_version        bigint       not null default 0,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);

create table scenario_data_file (
    id            bigserial primary key,
    scenario_id   bigint       not null references scenario (id) on delete cascade,
    path          varchar(500) not null,
    mode          varchar(10)  not null default 'SHARED' check (mode in ('SHARED', 'SPLIT')),
    has_header    boolean      not null default false,
    consumed      boolean      not null default true,
    unique (scenario_id, path)
);
