        // Enable Tab/Shift+Tab traversal out of the multi-line instructions text area
        {
            var im = instructionsArea.getInputMap(javax.swing.JComponent.WHEN_FOCUSED);
            var am = instructionsArea.getActionMap();
            instructionsArea.setFocusTraversalKeysEnabled(false);
            im.put(javax.swing.KeyStroke.getKeyStroke("TAB"), "focusNext");
            im.put(javax.swing.KeyStroke.getKeyStroke("shift TAB"), "focusPrev");
            am.put("focusNext", new javax.swing.AbstractAction()
            {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e)
                {
                    ((java.awt.Component) e.getSource()).transferFocus();
                }
            });
            am.put("focusPrev", new javax.swing.AbstractAction()
            {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e)
                {
                    ((java.awt.Component) e.getSource()).transferFocusBackward();
                }
            });
        }

        // Ensure key components are focusable
        actionButton.setFocusable(true);
        modeSwitch.setFocusable(true);
        if (codeCheckBox != null) codeCheckBox.setFocusable(true);
        if (searchProjectCheckBox != null) searchProjectCheckBox.setFocusable(true);
        micButton.setFocusable(true);
        modelSelector.getComponent().setFocusable(true);
        wandButton.setFocusable(true);

        setFocusTraversalPolicyProvider(true);
        setFocusTraversalPolicy(new InstructionsPanelFocusTraversalPolicy());
    }

    private class InstructionsPanelFocusTraversalPolicy extends FocusTraversalPolicy
    {
        private final List<Component> focusOrder = List.of(
            instructionsArea,
            modeSwitch,
            codeCheckBox,
            searchProjectCheckBox,
            actionButton,
            micButton,
            modelSelector.getComponent(),
            wandButton
        );

        @Override
        public Component getComponentAfter(Container aContainer, Component aComponent)
        {
            int idx = focusOrder.indexOf(aComponent);
            if (idx >= 0 && idx < focusOrder.size() - 1) {
                Component next = focusOrder.get(idx + 1);
                return (next != null && next.isVisible() && next.isEnabled()) ? next : getComponentAfter(aContainer, next);
            }
            // Wrap to first component or delegate to parent
            return getFirstComponent(aContainer);
        }

        @Override
        public Component getComponentBefore(Container aContainer, Component aComponent)
        {
            int idx = focusOrder.indexOf(aComponent);
            if (idx > 0) {
                Component prev = focusOrder.get(idx - 1);
                return (prev != null && prev.isVisible() && prev.isEnabled()) ? prev : getComponentBefore(aContainer, prev);
            }
            // Wrap to last component or delegate to parent
            return getLastComponent(aContainer);
        }

        @Override
        public Component getFirstComponent(Container aContainer)
        {
            return focusOrder.stream()
                    .filter(c -> c != null && c.isVisible() && c.isEnabled() && c.isFocusable())
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Component getLastComponent(Container aContainer)
        {
            for (int i = focusOrder.size() - 1; i >= 0; i--) {
                Component c = focusOrder.get(i);
                if (c != null && c.isVisible() && c.isEnabled() && c.isFocusable()) {
                    return c;
                }
            }
            return null;
        }

        @Override
        public Component getDefaultComponent(Container aContainer)
        {
            return getFirstComponent(aContainer);
        }
    }
}
