alter table load_profile
    add column if not exists folder varchar(120) not null default '';

create index if not exists idx_load_profile_script_folder
    on load_profile (script_id, folder);
