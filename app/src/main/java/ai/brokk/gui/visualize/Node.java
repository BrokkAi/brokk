package ai.brokk.gui.visualize;

import ai.brokk.analyzer.ProjectFile;

public class Node {
    public final ProjectFile file;
    public double x;
    public double y;
    public double vx;
    public double vy;
    public double radiusPx;

    public Node(ProjectFile file, double x, double y, double vx, double vy, double radiusPx) {
        this.file = file;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.radiusPx = radiusPx;
    }
}
