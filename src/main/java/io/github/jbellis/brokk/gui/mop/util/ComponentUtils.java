package io.github.jbellis.brokk.gui.mop.util;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

public class ComponentUtils {
    /**
     * Finds all components of a specific type within a container.
     */
    public static <T extends Component> List<T> findComponentsOfType(Container container, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component comp : container.getComponents()) {
            if (type.isInstance(comp)) {
                result.add(type.cast(comp));
            }
            if (comp instanceof Container subContainer) {
                result.addAll(findComponentsOfType(subContainer, type));
            }
        }
        return result;
    }
}
