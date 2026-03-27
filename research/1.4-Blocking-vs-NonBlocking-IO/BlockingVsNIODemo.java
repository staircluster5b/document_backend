import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Demo so sánh Blocking I/O vs NIO
 * Chạy: java BlockingVsNIODemo.java
 */
public class BlockingVsNIODemo {
    
    private static final int PORT = 8080;
    private static final String TEST_MESSAGE = "Hello from client!\n";
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Java I/O Models Demo ===\n");
        
        // Demo 1: Buffer operations
        demoBufferOperations();
        
        // Demo 2: Blocking I/O Server (chạy nhanh)
        System.out.println("\n--- Blocking I/O Demo ---");
        demoBlockingIO();
        
        // Demo 3: NIO Non-blocking
        System.out.println("\n--- NIO Non-blocking Demo ---");
        demoNIO();
        
        // Demo 4: Performance comparison
        System.out.println("\n--- Performance Comparison ---");
        comparePerformance();
    }
    
    /**
     * Demo 1: ByteBuffer operations và state transitions
     */
    static void demoBufferOperations() {
        System.out.println("=== Demo 1: ByteBuffer State Machine ===\n");
        
        ByteBuffer buffer = ByteBuffer.allocate(16);
        printBufferState(buffer, "Initial state");
        
        // Put data
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        printBufferState(buffer, "After put 'Hello'");
        
        // Flip to read mode
        buffer.flip();
        printBufferState(buffer, "After flip()");
        
        // Read 3 bytes
        byte[] dest = new byte[3];
        buffer.get(dest);
        System.out.println("Read: " + new String(dest));
        printBufferState(buffer, "After read 3 bytes");
        
        // Compact (move remaining to front)
        buffer.compact();
        printBufferState(buffer, "After compact()");
        
        // Clear
        buffer.clear();
        printBufferState(buffer, "After clear()");
        
        System.out.println();
    }
    
    static void printBufferState(ByteBuffer buf, String label) {
        System.out.printf("%-25s | Position: %2d | Limit: %2d | Capacity: %2d | Remaining: %2d%n",
            label, buf.position(), buf.limit(), buf.capacity(), buf.remaining());
    }
    
    /**
     * Demo 2: Blocking I/O - Mỗi connection = 1 thread
     */
    static void demoBlockingIO() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Blocking Server started on port " + PORT);
        
        // Chạy server trong thread riêng
        Future<?> serverFuture = executor.submit(() -> {
            try {
                // Chỉ accept 1 connection cho demo
                Socket client = serverSocket.accept();
                System.out.println("[Blocking] Client connected: " + client.getInetAddress());
                
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));
                String line = in.readLine();
                System.out.println("[Blocking] Received: " + line);
                
                PrintWriter out = new PrintWriter(client.getOutputStream(), true);
                out.println("Blocking server response");
                
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        
        // Client connect
        Thread.sleep(100); // Đợi server start
        try (Socket client = new Socket("localhost", PORT);
             PrintWriter out = new PrintWriter(client.getOutputStream(), true);
             BufferedReader in = new BufferedReader(
                 new InputStreamReader(client.getInputStream()))) {
            
            out.println(TEST_MESSAGE.trim());
            String response = in.readLine();
            System.out.println("[Blocking] Client received: " + response);
        }
        
        serverFuture.get(2, TimeUnit.SECONDS);
        serverSocket.close();
        executor.shutdown();
    }
    
    /**
     * Demo 3: NIO với Selector - Single thread xử lý nhiều connections
     */
    static void demoNIO() throws Exception {
        Selector selector = Selector.open();
        
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(PORT + 1));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        
        System.out.println("NIO Server started on port " + (PORT + 1));
        
        // Server thread
        Thread serverThread = new Thread(() -> {
            try {
                while (true) {
                    selector.select(1000); // Timeout 1s
                    
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        
                        if (!key.isValid()) continue;
                        
                        if (key.isAcceptable()) {
                            ServerSocketChannel server = (ServerSocketChannel) key.channel();
                            SocketChannel client = server.accept();
                            client.configureBlocking(false);
                            client.register(selector, SelectionKey.OP_READ);
                            System.out.println("[NIO] Client connected (non-blocking)");
                            
                        } else if (key.isReadable()) {
                            SocketChannel client = (SocketChannel) key.channel();
                            ByteBuffer buf = ByteBuffer.allocate(256);
                            int bytesRead = client.read(buf);
                            
                            if (bytesRead == -1) {
                                key.cancel();
                                client.close();
                                continue;
                            }
                            
                            buf.flip();
                            String msg = StandardCharsets.UTF_8.decode(buf).toString().trim();
                            System.out.println("[NIO] Received: " + msg);
                            
                            // Gửi response
                            buf.clear();
                            buf.put("NIO server response\n".getBytes(StandardCharsets.UTF_8));
                            buf.flip();
                            client.write(buf);
                            
                            key.cancel(); // Đóng sau 1 message cho demo
                            client.close();
                            return; // Thoát sau 1 connection
                        }
                    }
                }
            } catch (IOException e) {
                // Ignore
            }
        });
        serverThread.start();
        
        // Client connect
        Thread.sleep(100);
        try (SocketChannel client = SocketChannel.open()) {
            client.connect(new InetSocketAddress("localhost", PORT + 1));
            
            ByteBuffer buf = ByteBuffer.wrap(TEST_MESSAGE.getBytes(StandardCharsets.UTF_8));
            client.write(buf);
            
            buf.clear();
            client.read(buf);
            buf.flip();
            String response = StandardCharsets.UTF_8.decode(buf).toString().trim();
            System.out.println("[NIO] Client received: " + response);
        }
        
        serverThread.join(2000);
        selector.close();
    }
    
    /**
     * Demo 4: So sánh Direct vs Heap Buffer performance
     */
    static void comparePerformance() throws Exception {
        int iterations = 100_000;
        int bufferSize = 1024;
        
        // Test Heap Buffer
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = ByteBuffer.allocate(bufferSize);
            buf.put(new byte[bufferSize]);
            buf.flip();
        }
        long heapTime = System.nanoTime() - start;
        
        // Test Direct Buffer
        start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(bufferSize);
            buf.put(new byte[bufferSize]);
            buf.flip();
        }
        long directTime = System.nanoTime() - start;
        
        System.out.printf("Heap Buffer:   %,d ms%n", heapTime / 1_000_000);
        System.out.printf("Direct Buffer: %,d ms%n", directTime / 1_000_000);
        System.out.printf("Ratio: Direct/Heap = %.2fx%n", (double) directTime / heapTime);
        
        // Test zero-copy file transfer
        System.out.println("\n--- Zero-copy File Transfer ---");
        demoZeroCopy();
    }
    
    static void demoZeroCopy() throws Exception {
        // Tạo file test
        File tempFile = File.createTempFile("test", ".dat");
        tempFile.deleteOnExit();
        
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] data = new byte[1024 * 1024]; // 1MB
            new Random().nextBytes(data);
            fos.write(data);
        }
        
        // Zero-copy transfer
        try (RandomAccessFile file = new RandomAccessFile(tempFile, "r");
             FileChannel source = file.getChannel();
             FileOutputStream dest = new FileOutputStream(tempFile.getAbsolutePath() + ".copy");
             FileChannel destChannel = dest.getChannel()) {
            
            long start = System.nanoTime();
            long transferred = source.transferTo(0, source.size(), destChannel);
            long time = System.nanoTime() - start;
            
            System.out.printf("Zero-copy transferred: %,d bytes in %,d μs%n", 
                transferred, time / 1000);
        }
        
        new File(tempFile.getAbsolutePath() + ".copy").delete();
    }
}
