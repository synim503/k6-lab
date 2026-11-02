package ru.k6lab.core.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Сериализация сообщений протокола в raw JSON. */
public final class ProtocolCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProtocolCodec() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String encode(Message message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Не удалось сериализовать " + message.getClass().getSimpleName(), e);
        }
    }

    public static Message decode(String json) {
        try {
            return MAPPER.readValue(json, Message.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Некорректное сообщение протокола: " + e.getOriginalMessage(), e);
        }
    }
}
