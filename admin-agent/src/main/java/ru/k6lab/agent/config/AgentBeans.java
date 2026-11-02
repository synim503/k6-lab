package ru.k6lab.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.k6lab.agent.journal.EventJournal;

@Configuration
public class AgentBeans {

    @Bean
    EventJournal eventJournal(AgentProperties props) {
        return new EventJournal(props.getHome().resolve("data"), props.getJournalMaxBytes());
    }
}
