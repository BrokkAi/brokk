import org.fife.ui.autocomplete.*;
import java.awt.event.KeyEvent;
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;

public class ListMethods {
    public static void main(String[] args) throws Exception {
        JTextArea textArea = new JTextArea();
        
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        Object defaultEnterKey = textArea.getInputMap(JComponent.WHEN_FOCUSED).get(enter);
        Action defaultEnterAction = defaultEnterKey != null ? textArea.getActionMap().get(defaultEnterKey) : null;
        
        AutoCompletion ac = new AutoCompletion(new DefaultCompletionProvider());
        ac.install(textArea);
        
        Action[] acceptAction = new Action[1];
        
        ac.addAutoCompletionListener(e -> {
            if (e.getEventType() == AutoCompletionEvent.Type.POPUP_SHOWN) {
                var inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED);
                var actionMap = textArea.getActionMap();
                Object actionKey = inputMap.get(enter);
                if (actionKey != null) {
                    Action currentAction = actionMap.get(actionKey);
                    // Don't wrap our own wrapper!
                    if (currentAction != null && !currentAction.getClass().getName().contains("MyEnterAction")) {
                        acceptAction[0] = currentAction;
                        
                        class MyEnterAction extends AbstractAction {
                            public void actionPerformed(ActionEvent evt) {
                                ac.hideChildWindows();
                                if (defaultEnterAction != null) {
                                    defaultEnterAction.actionPerformed(evt);
                                } else {
                                    textArea.replaceSelection("\n");
                                }
                            }
                        }
                        
                        actionMap.put(actionKey, new MyEnterAction());
                    }
                }
            }
        });
        System.out.println("Compiles!");
    }
}
