-- Профилирование и тюнинг ОС попадают в журнал операций над генераторами
alter table deployment drop constraint if exists deployment_operation_check;
alter table deployment add constraint deployment_operation_check
    check (operation in ('PREFLIGHT', 'INSTALL', 'UPDATE', 'RESTART', 'UNINSTALL', 'REDEPLOY', 'PROFILE', 'TUNE_APPLY', 'TUNE_ROLLBACK'));
