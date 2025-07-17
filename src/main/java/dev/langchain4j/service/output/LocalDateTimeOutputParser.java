package dev.langchain4j.service.output;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;

import java.time.LocalDateTime;

import dev.langchain4j.Internal;

@Internal
class LocalDateTimeOutputParser implements OutputParser<LocalDateTime> {

    @Override
    public LocalDateTime parse(String string) {
        return LocalDateTime.parse(string.trim(), ISO_LOCAL_DATE_TIME);
    }

    @Override
    public String formatInstructions() {
        return "yyyy-MM-ddTHH:mm:ss";
    }
}
