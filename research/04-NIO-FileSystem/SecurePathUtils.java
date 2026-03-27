import java.io.IOException;
import java.nio.file.*;

/**
 * Demo 3: Path Manipulation & Security
 * 
 * Bảo vệ chống lại:
 * - Path Traversal Attacks (../../../etc/passwd)
 * - Symlink Escapes
 * - Null byte injection
 */
public class SecurePathUtils {
    
    /**
     * Validate path không vượt ra ngoài base directory.
     * Ngăn chặn path traversal attacks.
     * 
     * @param base Directory gốc được phép truy cập
     * @param userInput Input từ user (KHÔNG ĐƯỢC TIN TƯỞNG)
     * @return Path đã được validate
     * @throws SecurityException nếu phát hiện traversal
     */
    public static Path safeResolve(Path base, String userInput) {
        // Bước 0: Kiểm tra null/empty
        if (userInput == null || userInput.trim().isEmpty()) {
            throw new IllegalArgumentException("Input không được rỗng");
        }
        
        // Bước 1: Parse và normalize
        // normalize() giải quyết . và ..
        Path resolved = base.resolve(userInput).normalize();
        
        // Bước 2: Kiểm tra vẫn nằm trong base
        // startsWith() so sánh từng component, không phải string prefix
        if (!resolved.startsWith(base.normalize())) {
            throw new SecurityException("🚫 Path traversal detected: " + userInput);
        }
        
        // Bước 3: Kiểm tra không phải symbolic link ra ngoài
        // toRealPath() theo dõi symlink và trả về canonical path
        try {
            Path realPath = resolved.toRealPath();
            Path realBase = base.toRealPath();
            if (!realPath.startsWith(realBase)) {
                throw new SecurityException("🚫 Symlink escape detected: " + userInput);
            }
        } catch (IOException e) {
            // File chưa tồn tại - OK cho operations tạo mới
            // Nhưng vẫn cần kiểm tra parent
            try {
                Path parent = resolved.getParent();
                if (parent != null) {
                    Path realParent = parent.toRealPath();
                    Path realBase = base.toRealPath();
                    if (!realParent.startsWith(realBase)) {
                        throw new SecurityException("🚫 Parent escape detected: " + userInput);
                    }
                }
            } catch (IOException ex) {
                throw new SecurityException("❌ Cannot validate path: " + ex.getMessage());
            }
        }
        
        return resolved;
    }
    
    /**
     * Tạo relative path giữa 2 paths, xử lý cross-platform.
     */
    public static Path createRelative(Path from, Path to) {
        return from.relativize(to);
    }
    
    /**
     * Lấy extension của file một cách an toàn.
     */
    public static String getExtension(Path path) {
        String filename = path.getFileName().toString();
        int lastDot = filename.lastIndexOf('.');
        return lastDot == -1 ? "" : filename.substring(lastDot + 1);
    }
    
    /**
     * Validate file extension cho phép.
     */
    public static void validateExtension(Path path, String... allowedExtensions) {
        String ext = getExtension(path).toLowerCase();
        for (String allowed : allowedExtensions) {
            if (ext.equals(allowed.toLowerCase())) {
                return;
            }
        }
        throw new SecurityException("🚫 Extension không được phép: ." + ext);
    }
    
    public static void main(String[] args) {
        System.out.println("=== PATH TRAVERSAL TESTS ===\n");
        
        Path base = Paths.get("/var/www/uploads").toAbsolutePath();
        System.out.println("Base directory: " + base);
        System.out.println();
        
        // Test cases
        String[][] testCases = {
            {"document.pdf", "✅ OK - Normal file"},
            {"subdir/image.png", "✅ OK - Subdirectory"},
            {"../../../etc/passwd", "🚫 Block - Path traversal"},
            {"../secret.txt", "🚫 Block - Parent escape"},
            {"file.txt/../other.pdf", "✅ OK - Normalized to other.pdf"},
            {"./file.txt", "✅ OK - Current directory"},
            {"", "🚫 Block - Empty input"},
            {"normal.txt\u0000.exe", "⚠️ Special - Null byte (đã xử lý bởi JVM)"}
        };
        
        for (String[] test : testCases) {
            String input = test[0];
            String expected = test[1];
            
            try {
                Path result = safeResolve(base, input);
                System.out.printf("✅ Input: %-30s -> %s%n", 
                    "\"" + input + "\"", result.getFileName());
            } catch (SecurityException | IllegalArgumentException e) {
                System.out.printf("🚫 Input: %-30s -> %s%n", 
                    "\"" + input + "\"", e.getMessage());
            }
        }
        
        System.out.println("\n=== PATH MANIPULATION ===\n");
        
        Path p1 = Paths.get("/home/user/docs");
        Path p2 = Paths.get("/home/user/docs/work/report.pdf");
        System.out.println("From: " + p1);
        System.out.println("To:   " + p2);
        System.out.println("Relative: " + createRelative(p1, p2));
        
        System.out.println("\n=== EXTENSION VALIDATION ===\n");
        
        Path file = Paths.get("document.PDF");
        try {
            validateExtension(file, "pdf", "doc", "txt");
            System.out.println("✅ Extension hợp lệ: " + file);
        } catch (SecurityException e) {
            System.out.println(e.getMessage());
        }
    }
}
