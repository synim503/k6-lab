-- Локальные переменные конкретного графика отчёта (JSON-объект {"name": "value"}).
-- Перекрывают общие переменные скрипта (chart_variable), позволяя вставлять одну панель несколько раз с разными параметрами.
alter table chart_template add column if not exists variables text not null default '{}';
