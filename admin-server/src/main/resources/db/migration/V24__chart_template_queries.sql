-- На одном графике может быть несколько запросов: JSON-массив [{"promql": "...", "legend": "..."}]; пусто - единственный запрос из promql
alter table chart_template add column queries text;
