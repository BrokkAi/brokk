package io.github.jbellis.brokk.analyzer.lsp;

import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class LspCallGraphHelper {

    /**
     * Finds all methods that call the method at the given location.
     *
     * @param methodLocation The location of the method to analyze.
     * @return A CompletableFuture that will resolve with a list of incoming calls (callers).
     */
    @NotNull
    public static CompletableFuture<List<CallHierarchyIncomingCall>> getCallers(@NotNull LspServer sharedServer, @NotNull Location methodLocation) {
        //  Prepare the call hierarchy to get a handle for the method.
        return prepareCallHierarchy(sharedServer, methodLocation).thenCompose(itemOpt -> {
            if (itemOpt.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            //  Use the handle to ask for incoming calls.
            CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(itemOpt.get());
            return sharedServer.query(server -> server.getTextDocumentService().callHierarchyIncomingCalls(params).join());
        });
    }

    /**
     * Finds all methods that are called by the method at the given location.
     *
     * @param methodLocation The location of the method to analyze.
     * @return A CompletableFuture that will resolve with a list of outgoing calls (callees).
     */
    @NotNull
    public static CompletableFuture<List<CallHierarchyOutgoingCall>> getCallees(@NotNull LspServer sharedServer, @NotNull Location methodLocation) {
        // Prepare the call hierarchy to get a handle for the method.
        return prepareCallHierarchy(sharedServer, methodLocation).thenCompose(itemOpt -> {
            if (itemOpt.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }
            // Use the handle to ask for outgoing calls.
            CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(itemOpt.get());
            return sharedServer.query(server -> server.getTextDocumentService().callHierarchyOutgoingCalls(params).join());
        });
    }

    /**
     * Helper method to perform the first step of the call hierarchy request.
     *
     * @param location The location of the symbol to prepare.
     * @return A CompletableFuture resolving to an Optional containing the CallHierarchyItem handle.
     */
    public static CompletableFuture<Optional<CallHierarchyItem>> prepareCallHierarchy(@NotNull LspServer sharedServer, Location location) {
        return sharedServer.query(server -> {
            CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                    new TextDocumentIdentifier(location.getUri()),
                    location.getRange().getStart()
            );
            // The server returns a list, but we only care about the first item for a given position.
            return server.getTextDocumentService().prepareCallHierarchy(params);
        }).thenApply(items -> items.join().stream().findFirst());
    }


}
