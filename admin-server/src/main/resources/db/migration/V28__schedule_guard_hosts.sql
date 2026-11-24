-- Расширяем ssh_host до text, чтобы хранить список серверов (по одному на строку или через запятую, опционально host:port).
alter table schedule_guard alter column ssh_host type text;
