import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo 1: Safe FileVisitor với Progress Tracking
 * 
 * Tính năng:
 * - Thread-safe statistics (AtomicLong)
 * - Depth limiting
 * - Graceful error handling
 * - Bỏ qua symlink loops
 */
public class SafeFileTreeWalker extends SimpleFileVisitor<Path> {
    private final AtomicLong fileCount = new AtomicLong(0);
    private final AtomicLong totalSize = new AtomicLong(0);
    private final long maxDepth;
    private final Path basePath;
    
    public SafeFileTreeWalker(Path basePath, long maxDepth) {
        this.basePath = basePath;
        this.maxDepth = maxDepth;
    }
    
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) {
            fileCount.incrementAndGet();
            totalSize.addAndGet(attrs.size());
            
            // Progress indicator mỗi 1000 files
            long count = fileCount.get();
            if (count % 1000 == 0) {
                System.out.println("Đã xử lý: " + count + " files...");
            }
        }
        return FileVisitResult.CONTINUE;
    }
    
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        // Không throw exception - log và tiếp tục
        System.err.println("⚠️ Không thể truy cập: " + file + " - " + exc.getMessage());
        return FileVisitResult.CONTINUE;
    }
    
    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        long depth = basePath.relativize(dir).getNameCount();
        if (depth > maxDepth) {
            System.out.println("↳ Bỏ qua (quá sâu): " + dir);
            return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
    }
    
    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
        if (exc != null) {
            System.err.println("⚠️ Lỗi sau khi duyệt: " + dir + " - " + exc.getMessage());
        }
        return FileVisitResult.CONTINUE;
    }
    
    public void printStats() {
        long count = fileCount.get();
        long size = totalSize.get();
        String sizeFormatted = formatBytes(size);
        
        System.out.println("\n=== STATISTICS ===");
        System.out.printf("Tổng files: %,d%n", count);
        System.out.printf("Tổng size: %s (%d bytes)%n", sizeFormatted, size);
        System.out.printf("Trung bình: %s/file%n", count > 0 ? formatBytes(size / count) : "0");
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.2f %sB", bytes / Math.pow(1024, exp), unit);
    }
    
    public static void main(String[] args) throws IOException {
        // Sử dụng thư mục hiện tại hoặc argument
        Path start = args.length > 0 ? Paths.get(args[0]) : Paths.get(".");
        int maxDepth = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        
        System.out.println("🔍 Bắt đầu duyệt: " + start.toAbsolutePath());
        System.out.println("📊 Max depth: " + maxDepth);
        System.out.println();
        
        SafeFileTreeWalker walker = new SafeFileTreeWalker(start, maxDepth);
        
        long startTime = System.currentTimeMillis();
        Files.walkFileTree(start, walker);
        long duration = System.currentTimeMillis() - startTime;
        
        walker.printStats();
        System.out.printf("⏱️  Thờ gian: %,d ms%n", duration);
    }
}
