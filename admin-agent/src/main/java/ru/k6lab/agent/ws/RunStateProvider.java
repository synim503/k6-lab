package ru.k6lab.agent.ws;

import ru.k6lab.core.protocol.Messages;

import java.util.List;

/** Состояние run на агенте: для HELLO (сверка с сервером после реконнекта) и heartbeat. */
public interface RunStateProvider {

    RunStateProvider NONE = new RunStateProvider() {
        @Override
        public List<Messages.AgentRun> runs() {
            return List.of();
        }

        @Override
        public List<String> activeRunIds() {
            return List.of();
        }
    };

    List<Messages.AgentRun> runs();

    List<String> activeRunIds();
}
