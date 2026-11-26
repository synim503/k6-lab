-- Каскадные внешние ключи на script и run, чтобы при удалении скрипта/запуска не оставались осиротевшие записи
delete from scheduled_run where script_id not in (select id from script);
update scheduled_run set run_id = null where run_id is not null and run_id not in (select id from run);
delete from load_profile where script_id not in (select id from script);
delete from chart_template where script_id not in (select id from script);
delete from chart_variable where script_id not in (select id from script);

alter table scheduled_run
    add constraint fk_scheduled_run_script foreign key (script_id) references script(id) on delete cascade,
    add constraint fk_scheduled_run_run foreign key (run_id) references run(id) on delete set null;

alter table load_profile
    add constraint fk_load_profile_script foreign key (script_id) references script(id) on delete cascade;

alter table chart_template
    add constraint fk_chart_template_script foreign key (script_id) references script(id) on delete cascade;

alter table chart_variable
    add constraint fk_chart_variable_script foreign key (script_id) references script(id) on delete cascade;
