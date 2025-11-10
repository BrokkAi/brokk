package ai.brokk.gui;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Constructor;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextArea;

/**
 * Minimal headless self-test for the Chrome focus traversal policy to ensure
 * that tabbing from the Instructions area proceeds to Action (submit) then Model.
 *
 * This uses reflection to instantiate Chrome.ChromeFocusTraversalPolicy without
 * constructing a full Chrome instance. Run with assertions enabled (-ea).
 */
public final class FocusTraversalOrderTest {
    public static void main(String[] args) throws Exception {
        // Synthetic components to mimic the key actors
        JTextArea instructions = new JTextArea();
        JButton action = new JButton("Go");
        JButton model = new JButton("Model");
        JButton mic = new JButton("Mic");
        JButton wand = new JButton("Wand");
        JButton history = new JButton("History");

        // Place components in a simple container so isShowing() can be true
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setLayout(new java.awt.FlowLayout());
        frame.add(instructions);
        frame.add(action);
        frame.add(model);
        frame.add(mic);
        frame.add(wand);
        frame.add(history);
        frame.pack();
        frame.setVisible(true);

        List<Component> order = List.of(
                instructions, action, model, mic, wand, history
        );

        // Reflective access to private static inner class ChromeFocusTraversalPolicy
        Class<?> policyClass = Class.forName("ai.brokk.gui.Chrome$ChromeFocusTraversalPolicy");
        Constructor<?> ctor = policyClass.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        Object policy = ctor.newInstance(order);

        var getAfter = policyClass.getMethod("getComponentAfter", Container.class, Component.class);
        var getBefore = policyClass.getMethod("getComponentBefore", Container.class, Component.class);

        Component nextFromInstructions = (Component) getAfter.invoke(policy, frame, instructions);
        assert nextFromInstructions == action : "Tab from Instructions should go to Action/Submit";

        Component secondFromInstructions = (Component) getAfter.invoke(policy, frame, nextFromInstructions);
        assert secondFromInstructions == model : "Second tab should go to Model selector";

        Component prevFromAction = (Component) getBefore.invoke(policy, frame, action);
        assert prevFromAction == instructions : "Shift+Tab from Action should return to Instructions";

        frame.dispose();
        System.out.println("FocusTraversalOrderTest passed.");
    }
}


