-- Жёсткий редеплой генератора: новая операция в журнале операций
alter table deployment drop constraint if exists deployment_operation_check;
alter table deployment add constraint deployment_operation_check
    check (operation in ('PREFLIGHT', 'INSTALL', 'UPDATE', 'RESTART', 'UNINSTALL', 'REDEPLOY'));
