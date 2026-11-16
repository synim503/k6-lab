-- Именованные профили нагрузки скрипта: операции, сценарии, SLA и переменные целиком (JSON формы «Нагрузка»)
create table load_profile (
    id bigserial primary key,
    script_id bigint not null,
    name varchar(120) not null,
    data text not null,
    created_at timestamptz not null default now(),
    created_by text,
    updated_at timestamptz not null default now(),
    updated_by text,
    unique (script_id, name)
);
