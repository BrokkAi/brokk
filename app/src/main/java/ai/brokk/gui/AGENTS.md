# GUI standards

1. **Swing thread safety**: Public methods that deal with Swing components should either assert they are being run on the EDT, or wrap in SwingUtilities.invokeLater. (Prefer `SwingUtil.runOnEdt(Callable<T> task, T defaultValue)` or `SwingUtil.runOnEdt(Runnable task)` to `SwingUtilities.invokeAndWait` when blocking for the result.)
1. **Never block on the EDT**: do not call get() or join() against a Future or similar object. Do not use synchronized blocks or wait for lock acquisition. Any method that does these should assert that it is not on the EDT.
1. **Notifications**: Use IConsoleIO.showNotification (also EDT-safe) for informational messages, and IConsoleIO.toolError for modal errors. If you do not have the IConsoleIO API available in the Workspace, stop and ask the user to provide it. Both methods are EDT-safe.
1. **Named components**: Avoid navigating component hierarchies to retrieve a specific component by index or text. Save a reference as a field instead.
1. **Dialog Utilities**: use MaterialOptionPane.showOptionDialog as a drop-in replacement for JOptionPane.showOptionDialog, and IConsoleIo::showConfirmDialog instead of JOptionPane.showOptionDialog.
1. **Dialogs**: When building dialogs, place buttons on the bottom. Start with a primary action button such as Ok or Done. It should have the following function applied to it ai.brokk.gui.SwingUtil.applyPrimaryButtonStyle(javax.swing.AbstractButton b). Next it should have a cancel button which is a normal ai.brokk.gui.components.MaterialButton with the text Cancel.
1. **Buttons**: Use ai.brokk.gui.components.MaterialButton instead of JButton. Use ai.brokk.gui.components.MaterialToggleButton instead of JToggleButton.
1. **Tables**: When adding context menus, right-click should select the row under the cursor iff none is already selected.
