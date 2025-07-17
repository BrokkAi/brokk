package dev.langchain4j.service.output;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;

import java.time.LocalDate;

import dev.langchain4j.Internal;

@Internal
class LocalDateOutputParser implements OutputParser<LocalDate> {

    @Override
    public LocalDate parse(String string) {
        return LocalDate.parse(string.trim(), ISO_LOCAL_DATE);
    }

    @Override
    public String formatInstructions() {
        return "yyyy-MM-dd";
    }
}
