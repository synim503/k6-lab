package ru.k6lab.server.deploy;

/** Тексты unit-файлов, скриптов и конфигов, которые ставятся на генератор. */
final class Units {

    static final String SERVICE_NAME = "k6-agent";
    static final String SYSTEM_USER = "k6agent";
    static final String CRON_MARK = "k6-agent-managed";
    static final String SYSCTL_FILE = "/etc/sysctl.d/99-k6.conf";

    private Units() {
    }

    /** Команда запуска агента: бинарь Go (без JRE) либо Java-агент из jar. */
    static String agentCommand(String dir, boolean nativeAgent) {
        return nativeAgent ? dir + "/k6-agent --agent.home=" + dir
                : dir + "/jre/bin/java -Xmx256m -XX:+ExitOnOutOfMemoryError -jar " + dir + "/agent.jar --agent.home=" + dir;
    }

    static String systemUnit(String dir, boolean nativeAgent) {
        return """
                [Unit]
                Description=k6-lab agent
                After=network-online.target
                Wants=network-online.target

                [Service]
                Type=simple
                User=%s
                WorkingDirectory=%s
                ExecStart=%s
                Restart=always
                RestartSec=3
                SuccessExitStatus=143
                LimitNOFILE=1048576
                LimitNPROC=infinity

                [Install]
                WantedBy=multi-user.target
                """.formatted(SYSTEM_USER, dir, agentCommand(dir, nativeAgent));
    }

    static String userUnit(String dir, boolean nativeAgent) {
        return """
                [Unit]
                Description=k6-lab agent (user)
                After=network-online.target

                [Service]
                Type=simple
                WorkingDirectory=%s
                ExecStart=%s
                Restart=always
                RestartSec=3
                SuccessExitStatus=143
                LimitNOFILE=1048576

                [Install]
                WantedBy=default.target
                """.formatted(dir, agentCommand(dir, nativeAgent));
    }

    static String agentConfig(String serverUrl, String token, String agentId, String dir) {
        return """
                agent.server-url=%s
                agent.token=%s
                agent.agent-id=%s
                agent.home=%s
                logging.file.name=%s/logs/agent.log
                logging.logback.rollingpolicy.max-file-size=10MB
                logging.logback.rollingpolicy.max-history=5
                """.formatted(serverUrl, token, agentId, dir, dir);
    }

    /** Супервизор для пользовательского режима без systemd --user (cron). */
    static String ctlScript(boolean nativeAgent) {
        String marker = nativeAgent ? "k6-agent" : "agent.jar";
        return """
                #!/bin/sh
                # Управление агентом k6-lab без systemd. Вызывается из cron: @reboot -> start, раз в минуту -> ensure.
                DIR="$(cd "$(dirname "$0")/.." && pwd)"
                PIDFILE="$DIR/data/agent.pid"
                LOCKFILE="$DIR/data/agent.lock"
                mkdir -p "$DIR/data" "$DIR/logs"
                is_running() {
                  [ -f "$PIDFILE" ] || return 1
                  pid="$(cat "$PIDFILE" 2>/dev/null)"
                  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
                  if [ -r "/proc/$pid/cmdline" ]; then
                    tr '\\0' ' ' < "/proc/$pid/cmdline" | grep -Fq "@@MARKER@@" || return 1
                  fi
                  return 0
                }
                start() {
                  is_running && return 0
                  cd "$DIR" || exit 1
                  nohup @@CMD@@ \
                    </dev/null >>"$DIR/logs/agent-stderr.log" 2>&1 &
                  echo $! > "$PIDFILE"
                }
                stop() {
                  if is_running; then
                    pid="$(cat "$PIDFILE")"
                    kill "$pid" 2>/dev/null
                    i=0
                    while is_running && [ $i -lt 20 ]; do sleep 1; i=$((i+1)); done
                    is_running && kill -9 "$pid" 2>/dev/null
                  fi
                  rm -f "$PIDFILE"
                }
                run_cmd() {
                  case "$1" in
                    start|ensure) start ;;
                    stop) stop ;;
                    restart) stop; start ;;
                    status) is_running && echo running || { echo stopped; exit 3; } ;;
                    *) echo "usage: $0 {start|stop|restart|ensure|status}"; exit 2 ;;
                  esac
                }
                if command -v flock >/dev/null 2>&1 && [ "$1" != "status" ]; then
                  ( flock -w 15 9 || exit 1; run_cmd "$1" ) 9>"$LOCKFILE"
                else
                  run_cmd "$1"
                fi
                """.replace("@@MARKER@@", marker).replace("@@CMD@@", nativeAgent ? "\"$DIR/k6-agent\" --agent.home=\"$DIR\""
                : "\"$DIR/jre/bin/java\" -Xmx256m -XX:+ExitOnOutOfMemoryError -jar \"$DIR/agent.jar\" --agent.home=\"$DIR\"");
    }

    static String sysctl() {
        return """
                # k6-lab: тюнинг для нагрузочного генератора
                net.ipv4.ip_local_port_range = 1024 65535
                net.ipv4.tcp_tw_reuse = 1
                net.core.somaxconn = 65535
                fs.file-max = 2097152
                """;
    }
}
