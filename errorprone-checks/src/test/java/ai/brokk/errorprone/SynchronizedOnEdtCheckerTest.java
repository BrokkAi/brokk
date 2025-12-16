package ai.brokk.errorprone;

import com.google.errorprone.CompilationTestHelper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SynchronizedOnEdtChecker using Error Prone's CompilationTestHelper.
 */
public class SynchronizedOnEdtCheckerTest {

    private final CompilationTestHelper helper = CompilationTestHelper.newInstance(
                    SynchronizedOnEdtChecker.class, getClass())
            .setArgs(List.of("--release", "21"));

    @Test
    public void warnsSynchronizedMethodInInvokeLater() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    SwingUtilities.invokeLater(() -> {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsSynchronizedMethodInEdtCheck() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    if (SwingUtilities.isEventDispatchThread()) {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsSynchronizedMethodInActionListener() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import java.awt.event.ActionListener;",
                        "import java.awt.event.ActionEvent;",
                        "import javax.swing.JButton;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void setup() {",
                        "    JButton button = new JButton();",
                        "    button.addActionListener(e -> {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsSynchronizedMethodInMouseListener() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import java.awt.event.MouseListener;",
                        "import java.awt.event.MouseEvent;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  class MyListener implements MouseListener {",
                        "    public void mouseClicked(MouseEvent e) {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    }",
                        "    public void mousePressed(MouseEvent e) {}",
                        "    public void mouseReleased(MouseEvent e) {}",
                        "    public void mouseEntered(MouseEvent e) {}",
                        "    public void mouseExited(MouseEvent e) {}",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsSynchronizedMethodInListSelectionListener() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.event.ListSelectionListener;",
                        "import javax.swing.event.ListSelectionEvent;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  class MyListener implements ListSelectionListener {",
                        "    @Override",
                        "    public void valueChanged(ListSelectionEvent e) {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnNonSynchronizedMethod() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "class MyClass {",
                        "  void regularMethod() {}",
                        "  void caller() {",
                        "    SwingUtilities.invokeLater(() -> {",
                        "      regularMethod();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnSynchronizedOutsideEdt() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    syncMethod();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void doesNotWarnSynchronizedInElseBranch() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    if (SwingUtilities.isEventDispatchThread()) {",
                        "      System.out.println(\"On EDT\");",
                        "    } else {",
                        "      syncMethod();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsSynchronizedExternalClass() {
        helper.addSourceLines(
                        "test/SyncClass.java",
                        "package test;",
                        "public class SyncClass {",
                        "  public synchronized void doSomething() {}",
                        "}")
                .addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import javax.swing.SwingUtilities;",
                        "class MyClass {",
                        "  void caller(SyncClass sc) {",
                        "    SwingUtilities.invokeLater(() -> {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      sc.doSomething();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsEventQueueInvokeLater() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import java.awt.EventQueue;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    EventQueue.invokeLater(() -> {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void warnsEventQueueIsDispatchThread() {
        helper.addSourceLines(
                        "test/MyClass.java",
                        "package test;",
                        "import java.awt.EventQueue;",
                        "class MyClass {",
                        "  synchronized void syncMethod() {}",
                        "  void caller() {",
                        "    if (EventQueue.isDispatchThread()) {",
                        "      // BUG: Diagnostic contains: synchronized method from EDT",
                        "      syncMethod();",
                        "    }",
                        "  }",
                        "}")
                .doTest();
    }
}
