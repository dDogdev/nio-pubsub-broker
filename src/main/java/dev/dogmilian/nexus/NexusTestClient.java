package dev.dogmilian.nexus;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class NexusTestClient {
    public static void main(String[] args) throws Exception {
        System.out.println("[CLIENT] Connecting to Nexus on port 9090...");
        try (Socket socket = new Socket("127.0.0.1", 9090)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            
            System.out.println("[CLIENT] Connected! Sending PUB frame...");
            // Frame Layout:
            // [0-1] Magic: 0x4E 0x4D
            // [2] Flags: 0x01 (PUB)
            // [3] TopicHash: 0x42
            // [4-7] Payload Length: 4
            // Payload: "TEST"
            
            byte[] payload = "TEST".getBytes();
            ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length);
            buffer.put((byte) 0x4E); // 'N'
            buffer.put((byte) 0x4D); // 'M'
            buffer.put((byte) 0x01); // PUB
            buffer.put((byte) 0x42); // Topic Hash
            buffer.putInt(payload.length);
            buffer.put(payload);
            
            out.write(buffer.array());
            out.flush();
            System.out.println("[CLIENT] PUB frame sent.");
            
            Thread.sleep(1000); // Give server time to process
            System.out.println("[CLIENT] Test finished.");
        }
    }
}
