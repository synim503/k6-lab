-- Профилирование хостов-генераторов и применённый тюнинг ОС (с сохранением исходных значений для отката).
create table host_profile (
    id             bigserial primary key,
    generator_id   bigint       not null references generator (id) on delete cascade,
    generator_name varchar(200) not null,
    started_at     timestamptz  not null default now(),
    finished_at    timestamptz,
    duration_sec   int          not null,
    status         varchar(20)  not null,
    error          text,
    metrics        jsonb,
    findings       jsonb
);
create index host_profile_gen_idx on host_profile (generator_id, id desc);

create table host_tuning (
    id             bigserial primary key,
    generator_id   bigint       not null references generator (id) on delete cascade,
    generator_name varchar(200) not null,
    applied_at     timestamptz  not null default now(),
    applied_by     varchar(100),
    rolled_back_at timestamptz,
    status         varchar(20)  not null,
    changes        jsonb        not null,
    error          text
);
create index host_tuning_gen_idx on host_tuning (generator_id, id desc);
