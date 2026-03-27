import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo 2: Production-Grade WatchService
 * 
 * Tính năng:
 * - Thread-safe với graceful shutdown
 * - Handle OVERFLOW events
 * - Non-blocking poll với timeout
 * - Auto-reset WatchKey
 */
public class RobustFileWatcher implements AutoCloseable {
    private final WatchService watchService;
    private final ExecutorService executor;
    private final ConcurrentHashMap<WatchKey, Path> keyToPath = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, FileChangeListener> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public RobustFileWatcher() throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-watcher");
            t.setDaemon(true); // Cho phép JVM exit khi main thread kết thúc
            return t;
        });
    }
    
    /**
     * Đăng ký watch cho một directory
     */
    public void watch(Path directory, FileChangeListener listener) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path phải là directory: " + directory);
        }
        
        WatchKey key = directory.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.OVERFLOW  // Quan trọng!
        );
        
        keyToPath.put(key, directory);
        listeners.put(directory, listener);
        System.out.println("👁️  Watching: " + directory);
    }
    
    public void start() {
        if (running.compareAndSet(false, true)) {
            executor.submit(this::watchLoop);
            System.out.println("▶️ WatchService started");
        }
    }
    
    private void watchLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                // Dùng poll với timeout để có thể interrupt
                key = watchService.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            if (key == null) continue; // Timeout, check running flag
            
            Path dir = keyToPath.get(key);
            FileChangeListener listener = listeners.get(dir);
            
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                // ⚠️ Xử lý OVERFLOW - event bị mất!
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    System.err.println("⚠️  OVERFLOW tại " + dir + ": Events bị mất, cần rescan!");
                    if (listener != null) {
                        listener.onOverflow(dir);
                    }
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                Path filename = ((WatchEvent<Path>) event).context();
                Path fullPath = dir.resolve(filename);
                int count = event.count(); // Số lần event được coalesce
                
                if (listener != null) {
                    listener.onChange(kind, fullPath, count);
                } else {
                    System.out.printf("[%s] %s (count=%d)%n", kind.name(), fullPath, count);
                }
            }
            
            // ⚠️ QUAN TRỌNG: Reset key để tiếp tục nhận events
            boolean valid = key.reset();
            if (!valid) {
                keyToPath.remove(key);
                listeners.remove(dir);
                System.out.println("🛑 WatchKey invalid, stopping watch for: " + dir);
            }
        }
        System.out.println("⏹️ WatchService stopped");
    }
    
    @Override
    public void close() {
        System.out.println("🔄 Đang đóng WatchService...");
        running.set(false);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("⚠️ Executor không shutdown đúng hạn");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        try {
            watchService.close();
            System.out.println("✅ WatchService đã đóng");
        } catch (IOException e) {
            System.err.println("❌ Lỗi đóng WatchService: " + e.getMessage());
        }
    }
    
    @FunctionalInterface
    public interface FileChangeListener {
        void onChange(WatchEvent.Kind<?> kind, Path path, int count);
        
        default void onOverflow(Path directory) {
            System.err.println("Overflow tại: " + directory);
        }
    }
    
    public static void main(String[] args) throws Exception {
        Path watchDir = args.length > 0 ? Paths.get(args[0]) : Paths.get("/tmp/watch-test");
        Files.createDirectories(watchDir);
        
        try (RobustFileWatcher watcher = new RobustFileWatcher()) {
            watcher.watch(watchDir, new FileChangeListener() {
                @Override
                public void onChange(WatchEvent.Kind<?> kind, Path path, int count) {
                    String icon = kind == StandardWatchEventKinds.ENTRY_CREATE ? "📝"
                                : kind == StandardWatchEventKinds.ENTRY_MODIFY ? "✏️"
                                : kind == StandardWatchEventKinds.ENTRY_DELETE ? "🗑️"
                                : "❓";
                    System.out.printf("%s [%s] %s (count=%d)%n", icon, kind.name(), path.getFileName(), count);
                }
                
                @Override
                public void onOverflow(Path directory) {
                    System.err.println("🌊 OVERFLOW! Cần rescan: " + directory);
                }
            });
            
            watcher.start();
            
            System.out.println("\n💡 Thử các lệnh sau trong terminal khác:");
            System.out.println("   touch " + watchDir + "/test.txt");
            System.out.println("   echo 'hello' >> " + watchDir + "/test.txt");
            System.out.println("   rm " + watchDir + "/test.txt");
            System.out.println("\nNhấn Enter để dừng...");
            System.in.read();
        }
    }
}
