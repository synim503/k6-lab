-- Этап 7: результаты run. Весь расчёт (ступени x операции, метрики SUT, кандидат максимума) хранится одним JSON:
-- он нужен для отчётов и сравнения, а Prometheus по истечении retention значений уже не отдаст.
create table run_result (
    run_id      bigint primary key references run (id) on delete cascade,
    data        jsonb       not null,
    computed_at timestamptz not null default now()
);
