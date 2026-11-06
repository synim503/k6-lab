-- Профили оптимизации: сохранённые наборы настроек хоста (параметр -> значение), которые можно редактировать и раскатывать на хосты.
create table tuning_profile (
    id          bigserial primary key,
    name        varchar(100) not null unique,
    description text,
    settings    jsonb        not null,
    created_by  varchar(100),
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);
