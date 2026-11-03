-- Модель нагрузки описывается прямо в скрипте: сценариев, моделей, профилей и SUT как отдельных сущностей больше нет.
-- Существующие данные переносятся: для каждого скрипта берётся последний изменённый сценарий.

alter table script
    add column env              jsonb            not null default '{}',
    add column stop_criteria    jsonb            not null default '{}',
    add column success_criteria jsonb            not null default '{}',
    add column level_pct        double precision not null default 100,
    add column ramp_sec         integer          not null default 30,
    add column hold_sec         integer          not null default 300;

create temp table picked as
    select distinct on (script_id) id as scenario_id, script_id, model_id, profile_id, env, stop_criteria, success_criteria
    from scenario
    order by script_id, updated_at desc;

update script s set env = p.env, stop_criteria = p.stop_criteria, success_criteria = p.success_criteria
from picked p where p.script_id = s.id;

update script s set level_pct = coalesce((tp.params ->> 'levelPct')::numeric, 100),
                    ramp_sec  = coalesce((tp.params ->> 'rampSec')::numeric, 30)::int,
                    hold_sec  = coalesce((tp.params ->> 'holdSec')::numeric, 300)::int
from picked p join test_profile tp on tp.id = p.profile_id
where p.script_id = s.id and tp.type = 'CONFIRMATION';

-- операции принадлежат скрипту; интенсивность хранится в оп/с
alter table load_operation add column script_id bigint references script (id) on delete cascade;
update load_operation o set script_id = p.script_id from picked p where p.model_id = o.model_id;
delete from load_operation where script_id is null;
alter table load_operation alter column script_id set not null;
update load_operation set intensity_value = intensity_value /
    case intensity_unit when 'PER_MIN' then 60.0 when 'PER_HOUR' then 3600.0 else 1.0 end;
alter table load_operation rename column intensity_value to rate_per_sec;
alter table load_operation
    drop column model_id,
    drop column mode,
    drop column intensity_unit,
    drop column share_pct,
    drop column pacing_ms,
    drop column vus_override,
    drop column pre_alloc_override,
    drop column max_vus_override,
    drop column env,
    drop column tags,
    drop column fixed_level_pct;
alter table load_operation add unique (script_id, name);

-- файлы данных
alter table scenario_data_file add column script_id bigint references script (id) on delete cascade;
update scenario_data_file d set script_id = p.script_id from picked p where p.scenario_id = d.scenario_id;
delete from scenario_data_file where script_id is null;
alter table scenario_data_file alter column script_id set not null;
alter table scenario_data_file drop column scenario_id;
alter table scenario_data_file rename to script_data_file;
alter table script_data_file add unique (script_id, path);

-- запуски ссылаются на скрипт
alter table run add column script_id bigint references script (id) on delete set null;
update run r set script_id = s.script_id from scenario s where s.id = r.scenario_id;
alter table run rename column scenario_name to script_name;
alter table run drop column scenario_id;
alter table run drop column is_baseline;
create index run_script_idx on run (script_id, queued_at desc);

drop table scenario;
drop table load_model;
drop table test_profile;
drop table profile_template;
drop table sut_metric;
drop table sut;

-- снимки старых запусков приводятся к новому виду (без сценария, модели, профиля и SUT)
update run set snapshot = (snapshot - 'scenario' - 'model' - 'profile' - 'sut')
    || jsonb_build_object(
        'env', coalesce(snapshot -> 'scenario' -> 'env', '{}'::jsonb),
        'operations', coalesce(snapshot -> 'model' -> 'operations', '[]'::jsonb),
        'gracefulStopSec', coalesce(snapshot -> 'profile' -> 'gracefulStopSec', to_jsonb(30)),
        'gracefulRampDownSec', coalesce(snapshot -> 'profile' -> 'gracefulRampDownSec', to_jsonb(30)));
