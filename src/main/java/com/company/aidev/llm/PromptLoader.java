package com.company.aidev.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Loads agent system prompts from {@code classpath:prompts/*.md}.
 *
 * <p>Keeping prompts out of the Java source makes them reviewable by non-developers and diffable in
 * merge requests, which matters as much as the code itself for an agent platform.
 */
@Component
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    /** Loads a prompt and replaces {@code {{key}}} placeholders. */
    public String load(String name, Map<String, String> variables) {
        String template = load(name);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String read(String name) {
        ClassPathResource resource = new ClassPathResource("prompts/" + name + ".md");
        if (!resource.exists()) {
            throw new IllegalStateException("Missing prompt resource: prompts/" + name + ".md");
        }
        try (var in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read prompt " + name, e);
        }
    }
}
