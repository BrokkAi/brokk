package ai.brokk;

import ai.brokk.gui.mop.webview.cef.CefAppProviderFactory;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.*;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAppHandlerAdapter;

/**
 * Minimal JCEF demo using JBR's bundled JCEF.
 * Run with: ./gradlew runJcefDemo
 */
public class JcefDemo {

    public static void main(String[] args) {
        System.out.println("*** Building CefApp... ***");
        CefApp cefApp = CefAppProviderFactory.getProvider().createCefApp(new CefAppHandlerAdapter(null) {

            @Override
            public void stateHasChanged(CefApp.CefAppState state) {
                System.out.println("*** CefApp state: " + state + " ***");
            }
        });
        System.out.println("*** CefApp created ***");

        CefClient client = cefApp.createClient();
        System.out.println("*** CefClient created ***");

        @SuppressWarnings("deprecation")
        CefBrowser browser = client.createBrowser("https://www.google.com", false, false);
        System.out.println("*** Browser created ***");

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("JCEF Demo - JBR Native");
            frame.setSize(1024, 768);

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBorder(BorderFactory.createLineBorder(Color.MAGENTA, 10));
            wrapper.add(browser.getUIComponent(), BorderLayout.CENTER);

            frame.add(wrapper, BorderLayout.CENTER);

            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    browser.close(true);
                    client.dispose();
                    cefApp.dispose();
                    frame.dispose();
                }
            });

            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            System.out.println("*** Window visible ***");
        });
    }
}
