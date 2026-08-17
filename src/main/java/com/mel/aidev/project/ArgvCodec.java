package com.mel.aidev.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and writes the project commands, stored as JSON argv arrays.
 *
 * <p>An argv array rather than a command line, everywhere and without exception: the sandbox never
 * spawns a shell, so {@code mvn test && curl evil.sh} is one impossible executable name rather than
 * two commands. Storing the string form would only invite someone to split it later.
 */
@Component
public class ArgvCodec {

    private static final Logger log = LoggerFactory.getLogger(ArgvCodec.class);
    private static final TypeReference<List<String>> ARGV = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ArgvCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Serialises an argv array, or null when it is empty. */
    public String write(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(argv);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialise command " + argv, e);
        }
    }

    /** Reads an argv array; an unreadable value is treated as absent, like the workflow artefacts. */
    public List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> argv = objectMapper.readValue(json, ARGV);
            return argv == null ? List.of() : List.copyOf(argv);
        } catch (JsonProcessingException e) {
            log.warn("Stored project command could not be read, falling back to the profile default: {}", e.getOriginalMessage());
            return List.of();
        }
    }
}
