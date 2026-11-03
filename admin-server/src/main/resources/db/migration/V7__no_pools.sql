-- Пулов и меток генераторов больше нет: запуск задаётся числом генераторов либо явным выбором хостов.
alter table generator drop column pool_id;
alter table generator drop column labels;
drop table generator_pool;
