package dev.langchain4j.internal;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import dev.langchain4j.Internal;

@Internal
public class Markers {

    private Markers() {}

    public static final Marker SENSITIVE = MarkerFactory.getMarker("SENSITIVE");
}
