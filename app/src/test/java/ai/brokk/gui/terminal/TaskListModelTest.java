package ai.brokk.gui.terminal;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.tasks.TaskList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.jupiter.api.Test;

public class TaskListModelTest {

    @Test
    public void delegatesSizeAndElementAt() {
        List<TaskList.TaskItem> items = new ArrayList<>();
        TaskList.TaskItem a = new TaskList.TaskItem("A", "A body", false);
        TaskList.TaskItem b = new TaskList.TaskItem("B", "B body", true);
        items.add(a);
        items.add(b);

        TaskListPanel.TaskListModel model = new TaskListPanel.TaskListModel(() -> items);

        assertEquals(items.size(), model.getSize());
        assertSame(a, model.getElementAt(0));
        assertSame(b, model.getElementAt(1));

        items.add(new TaskList.TaskItem("C", "C body", false));
        assertEquals(items.size(), model.getSize());
    }

    @Test
    public void fireRefreshNotifiesListeners() {
        List<TaskList.TaskItem> items = new ArrayList<>();
        items.add(new TaskList.TaskItem("A", "A body", false));
        items.add(new TaskList.TaskItem("B", "B body", true));

        TaskListPanel.TaskListModel model = new TaskListPanel.TaskListModel(() -> items);

        AtomicReference<ListDataEvent> lastEvent = new AtomicReference<>();
        model.addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {}

            @Override
            public void intervalRemoved(ListDataEvent e) {}

            @Override
            public void contentsChanged(ListDataEvent e) {
                lastEvent.set(e);
            }
        });

        model.fireRefresh();

        assertNotNull(lastEvent.get());
        assertEquals(0, lastEvent.get().getIndex0());
        assertEquals(items.size() - 1, lastEvent.get().getIndex1());
    }
}
