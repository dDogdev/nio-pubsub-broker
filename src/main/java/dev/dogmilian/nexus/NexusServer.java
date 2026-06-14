package dev.dogmilian.nexus;

import dev.dogmilian.nexus.network.MultiReactor;
import java.io.IOException;

public class NexusServer {
    public static void main(String[] args) throws IOException {
        int port = 9090;
        int numWorkers = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        
        // Ensure engine is booted via static init
        System.out.println("[NEXUS] Booting Global Engine...");
        var router = NexusGlobal.ROUTER; 
        
        System.out.println("[NEXUS] Starting MultiReactor on port " + port + " with " + numWorkers + " workers...");
        MultiReactor reactor = new MultiReactor(port, numWorkers);
        reactor.start();
    }
}
