package dev.langchain4j.service.output;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;

import java.time.LocalTime;

import dev.langchain4j.Internal;

@Internal
class LocalTimeOutputParser implements OutputParser<LocalTime> {

    @Override
    public LocalTime parse(String string) {
        return LocalTime.parse(string.trim(), ISO_LOCAL_TIME);
    }

    @Override
    public String formatInstructions() {
        return "HH:mm:ss";
    }
}
