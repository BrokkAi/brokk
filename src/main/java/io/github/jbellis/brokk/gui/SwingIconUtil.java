package io.github.jbellis.brokk.gui;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.util.Comparator;

/**
 * Utility helpers related to Swing icons.
 *
 * <p>Call {@link #printAvailableIcons()} to log every key in {@link UIManager#getDefaults()}
 * whose value is an {@link Icon}.  Useful for discovering what icons are exposed by the
 * current Look&nbsp;and&nbsp;Feel so you can reuse them in your application instead of
 * shipping duplicates.</p>
 */
public final class SwingIconUtil
{
    private static final Logger logger = LogManager.getLogger(SwingIconUtil.class);

    private SwingIconUtil() { /* no instances */ }

    /**
     * Logs every {@code UIManager} key backed by an {@link Icon}.  Each key is logged at INFO
     * level so it is always visible in standard application logs without requiring DEBUG mode.
     *
     * <p>Example usage:
     * <pre>{@code
     * SwingIconUtil.printAvailableIcons();
     * }</pre>
     * </p>
     *
     * <p>Because this only queries {@code UIManager.getDefaults()}, it is safe to call from
     * any thread (no EDT-only restriction).</p>
     */
    public static void printAvailableIcons()
    {
        var defaults = UIManager.getDefaults();
        
        System.out.println("=== All FlatLaf Theme Icons ===");
        System.out.println("Total UIManager entries: " + defaults.size());
        
        // Collect all keys that end with "Icon" or contain ".icon"
        var potentialIconKeys = defaults.keySet().stream()
                .map(Object::toString)
                .filter(key -> key.endsWith("Icon") || key.contains(".icon"))
                .sorted()
                .toList();
        
        System.out.println("Potential icon keys found: " + potentialIconKeys.size());
        System.out.println();
        
        // Try to load each icon and display its information
        var actualIcons = new java.util.ArrayList<java.util.Map.Entry<String, Icon>>();
        
        for (var key : potentialIconKeys) {
            try {
                var value = UIManager.get(key);
                if (value instanceof Icon icon) {
                    actualIcons.add(java.util.Map.entry(key, icon));
                }
            } catch (Exception e) {
                // Skip if unable to load
            }
        }
        
        System.out.println("Actual icons loaded: " + actualIcons.size());
        System.out.println();
        
        // Group icons by their class
        var iconsByClass = actualIcons.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getValue().getClass().getName(),
                        java.util.TreeMap::new,
                        java.util.stream.Collectors.toList()
                ));
        
        iconsByClass.forEach((className, icons) -> {
            System.out.println("\n" + className + " (" + icons.size() + " icons):");
            icons.forEach(entry -> {
                var icon = entry.getValue();
                System.out.println(String.format("  %-60s [%2dx%-2d]", 
                        entry.getKey(), 
                        icon.getIconWidth(), 
                        icon.getIconHeight()));
            });
        });
        
        // Summary of all FlatLaf specific icons
        System.out.println("\n=== FlatLaf Icon Summary ===");
        var flatIcons = actualIcons.stream()
                .filter(e -> e.getValue().getClass().getName().contains("flatlaf"))
                .toList();
        
        System.out.println("Total FlatLaf icons: " + flatIcons.size());
        
        // List unique FlatLaf icon classes
        System.out.println("\nUnique FlatLaf icon classes:");
        flatIcons.stream()
                .map(e -> e.getValue().getClass().getName())
                .distinct()
                .sorted()
                .forEach(className -> {
                    var count = flatIcons.stream()
                            .filter(e -> e.getValue().getClass().getName().equals(className))
                            .count();
                    System.out.println(String.format("  %-70s (%d instances)", className, count));
                });
    }

    /**
     * Main method to print all available UIManager icons.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args)
    {
        // Initialize Look and Feel for a richer set of icons
        com.formdev.flatlaf.FlatLightLaf.setup();
        printAvailableIcons();
    }
}
