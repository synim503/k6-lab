-- Этап 1: пользователи, SSH, дистрибутивы, генераторы, деплой, аудит, настройки.
-- Во всех редактируемых таблицах lock_version - оптимистичная блокировка (@Version).

create table app_user (
    id            bigserial primary key,
    username      varchar(100) not null unique,
    password_hash varchar(100) not null,
    role          varchar(20)  not null check (role in ('ADMIN', 'USER')),
    enabled       boolean      not null default true,
    lock_version  bigint       not null default 0,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now()
);

create table ssh_credential (
    id                  bigserial primary key,
    name                varchar(100) not null unique,
    username            varchar(100) not null,
    auth_type           varchar(20)  not null check (auth_type in ('PASSWORD', 'KEY')),
    secret_enc          bytea,
    passphrase_enc      bytea,
    sudo_password_enc   bytea,
    jump_host           varchar(255),
    jump_port           integer,
    jump_credential_id  bigint references ssh_credential (id),
    lock_version        bigint       not null default 0,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now()
);

create table known_host (
    id                 bigserial primary key,
    host               varchar(255) not null,
    port               integer      not null,
    key_type           varchar(50)  not null,
    fingerprint_sha256 varchar(100) not null,
    public_key         text         not null,
    confirmed_by       varchar(100),
    confirmed_at       timestamptz  not null default now(),
    unique (host, port)
);

create table distribution_artifact (
    id          bigserial primary key,
    kind        varchar(20)  not null check (kind in ('AGENT', 'K6', 'JRE', 'XK6')),
    label       varchar(100),
    version     varchar(50)  not null,
    arch        varchar(20)  not null check (arch in ('ANY', 'LINUX_AMD64', 'LINUX_ARM64')),
    filename    varchar(255) not null,
    storage_key varchar(500) not null,
    sha256      varchar(64)     not null,
    size_bytes  bigint       not null,
    is_default  boolean      not null default false,
    uploaded_by varchar(100),
    lock_version bigint      not null default 0,
    created_at  timestamptz  not null default now(),
    unique (kind, version, arch, label)
);

create table generator_pool (
    id           bigserial primary key,
    name         varchar(100) not null unique,
    lock_version bigint       not null default 0,
    created_at   timestamptz  not null default now()
);

create table generator (
    id                   bigserial primary key,
    name                 varchar(100) not null unique,
    host                 varchar(255) not null,
    ssh_port             integer      not null default 22,
    ssh_credential_id    bigint references ssh_credential (id),
    pool_id              bigint references generator_pool (id) on delete set null,
    labels               jsonb        not null default '[]',
    install_mode         varchar(10)  not null check (install_mode in ('SYSTEM', 'USER')),
    install_dir          varchar(255) not null,
    server_url           varchar(500) not null,
    max_concurrent_runs  integer      not null default 1 check (max_concurrent_runs >= 1),
    tune_os              boolean      not null default false,
    supervisor           varchar(20),
    status               varchar(20)  not null,
    maintenance          boolean      not null default false,
    agent_version        varchar(50),
    k6_version           varchar(50),
    arch                 varchar(20),
    os_name              varchar(200),
    one_time_token_hash  varchar(64),
    one_time_expires     timestamptz,
    agent_token_hash     varchar(64),
    last_heartbeat_at    timestamptz,
    last_hello_at        timestamptz,
    clock_offset_ms      bigint,
    last_seq             bigint       not null default 0,
    active_runs          integer      not null default 0,
    lock_version         bigint       not null default 0,
    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now()
);
create index generator_pool_idx on generator (pool_id);
create index generator_token_idx on generator (agent_token_hash);
create index generator_ot_token_idx on generator (one_time_token_hash);

create table generator_metrics (
    generator_id bigint      not null,
    ts           timestamptz not null,
    cpu          real,
    mem_used     bigint,
    mem_total    bigint,
    net_rx       bigint,
    net_tx       bigint,
    load1        real,
    disk_free    bigint,
    sockets      integer,
    active_runs  integer
);
create index generator_metrics_idx on generator_metrics (generator_id, ts desc);
create index generator_metrics_ts_idx on generator_metrics (ts);

create table deployment (
    id                  bigserial primary key,
    generator_id        bigint references generator (id) on delete set null,
    target_host         varchar(255) not null,
    operation           varchar(20)  not null check (operation in ('PREFLIGHT', 'INSTALL', 'UPDATE', 'RESTART', 'UNINSTALL')),
    status              varchar(30)  not null check (status in ('PENDING', 'RUNNING', 'WAITING_HOST_KEY', 'SUCCEEDED', 'FAILED', 'ROLLED_BACK')),
    steps               jsonb        not null default '[]',
    log                 text         not null default '',
    error               text,
    host_key_type       varchar(50),
    host_key_fingerprint varchar(100),
    host_key_public     text,
    host_key_host       varchar(255),
    host_key_port       integer,
    params              jsonb        not null default '{}',
    started_by          varchar(100),
    started_at          timestamptz  not null default now(),
    finished_at         timestamptz
);
create index deployment_generator_idx on deployment (generator_id, started_at desc);

create table setting (
    key        varchar(100) primary key,
    value      jsonb        not null,
    updated_at timestamptz  not null default now()
);

create table audit_log (
    id          bigserial primary key,
    ts          timestamptz  not null default now(),
    username    varchar(100),
    action      varchar(100) not null,
    entity_type varchar(100),
    entity_id   varchar(100),
    details     jsonb,
    ip          varchar(64)
);
create index audit_log_ts_idx on audit_log (ts desc);
