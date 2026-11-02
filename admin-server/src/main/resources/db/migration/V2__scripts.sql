-- Этап 2: скрипты, версии, Git.

create table git_repo (
    id                 bigserial primary key,
    name               varchar(100) not null unique,
    url                varchar(500) not null,
    auth_type          varchar(20)  not null check (auth_type in ('NONE', 'HTTPS_TOKEN', 'SSH')),
    token_enc          bytea,
    ssh_credential_id  bigint references ssh_credential (id),
    subdir             varchar(300) not null default '',
    lock_version       bigint       not null default 0,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now()
);

create table script (
    id               bigserial primary key,
    name             varchar(150) not null unique,
    description      varchar(1000),
    git_repo_id      bigint references git_repo (id) on delete set null,
    git_branch       varchar(200),
    git_entrypoint   varchar(300),
    lock_version     bigint       not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

-- Версия неизменяема: правка = новая версия. Содержимое всегда хранится как zip (одиночный .js оборачивается).
create table script_version (
    id                bigserial primary key,
    script_id         bigint       not null references script (id) on delete cascade,
    number            integer      not null,
    source            varchar(20)  not null check (source in ('UPLOAD', 'EDITOR', 'ROLLBACK', 'GIT')),
    comment           varchar(500),
    parent_version_id bigint,
    git_repo_id       bigint,
    git_ref           varchar(300),
    git_branch        varchar(200),
    commit_hash       varchar(64),
    storage_key       varchar(500) not null,
    entrypoint        varchar(300) not null,
    sha256            varchar(64)  not null,
    size_bytes        bigint       not null,
    inspect_status    varchar(20)  not null check (inspect_status in ('OK', 'FAILED', 'UNAVAILABLE')),
    inspect_result    jsonb,
    inspect_error     text,
    operations        jsonb        not null default '[]',
    valid_for_model   boolean      not null,
    invalid_reason    text,
    uses_helper       boolean      not null default false,
    warnings          jsonb        not null default '[]',
    files             jsonb        not null default '[]',
    created_by        varchar(100),
    created_at        timestamptz  not null default now(),
    unique (script_id, number)
);
create index script_version_script_idx on script_version (script_id, number desc);
