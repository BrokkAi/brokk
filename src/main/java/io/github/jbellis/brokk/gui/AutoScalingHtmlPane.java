package io.github.jbellis.brokk.gui;

import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;

public class AutoScalingHtmlPane {

    /** Plug-in editor kit */
    static class ScalingHTMLEditorKit extends HTMLEditorKit {
        // we keep the default parser, just swap the factory
        @Override public ViewFactory getViewFactory() {
            return new HTMLFactory() {
                @Override public View create(Element elem) {
                    View v = super.create(elem);
                    return (v instanceof ImageView) ? new ScalingImageView(elem) : v;
                }
            };
        }
    }

    /** The view that does the work */
    static class ScalingImageView extends ImageView {
        ScalingImageView(Element e) { super(e); }

        /** Paint the image shrunk if necessary */
        @Override public void paint(Graphics g, Shape a) {
            Rectangle r = (a instanceof Rectangle) ? (Rectangle) a : a.getBounds();
            Image img = getImage();
            if (img == null) return;

            int imgW = img.getWidth(null);
            int imgH = img.getHeight(null);
            if (imgW <= 0 || imgH <= 0) return;

            int avail = getContainer().getWidth()
                    - getContainer().getInsets().left
                    - getContainer().getInsets().right;
            float s = Math.min(1f, avail / (float) imgW);

            int w = Math.round(imgW * s);
            int h = Math.round(imgH * s);

            g.drawImage(img, r.x, r.y, w, h, getContainer());
        }

        /** Tell the layout engine about the scaled size */
        @Override public float getPreferredSpan(int axis) {
            Image img = getImage();
            if (img == null) return 0;

            int imgW = img.getWidth(null);
            int imgH = img.getHeight(null);

            // width is 0 the first time the view is laid out
            int avail = getContainer() == null ? 0
                                               : getContainer().getWidth()
                                - getContainer().getInsets().left
                                - getContainer().getInsets().right;

            if (avail <= 0) {              // pane not yet sized → skip scaling
                return axis == View.X_AXIS ? imgW : imgH;
            }

            float scale = Math.min(1f, avail / (float) imgW);
            return axis == View.X_AXIS ? imgW * scale : imgH * scale;
        }
    }
}
