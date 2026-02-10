package ai.brokk;

import ai.brokk.project.IProject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.File;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

public class OfflineService extends AbstractService {
    public OfflineService(IProject project) {
        super(project);
    }

    @Override
    public float getUserBalance() throws IOException {
        return 0;
    }

    @Override
    public void sendFeedback(
            String category, String feedbackText, boolean includeDebugLog, @Nullable File screenshotFile)
            throws IOException {}

    @Override
    public JsonNode reportClientException(JsonNode exceptionReport) throws IOException {
        return NullNode.getInstance();
    }
}
