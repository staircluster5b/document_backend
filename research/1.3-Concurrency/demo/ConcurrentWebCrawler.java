package demo;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Web Crawler sử dụng multiple concurrent collections
 * 
 * Architecture:
 * - BlockingQueue: Task queue cho URLs cần crawl
 * - ConcurrentSkipListSet: Dedup URLs đã crawl (sorted để debug)
 * - ConcurrentHashMap: Store kết quả
 * - CopyOnWriteArrayList: Event listeners (cấu hình ít thay đổi)
 */
public class ConcurrentWebCrawler {
    
    // Task queue với backpressure
    private final BlockingQueue<URI> urlQueue = new LinkedBlockingQueue<>(1000);
    
    // Visited URLs - SkipList cho sorted ordering khi debug
    private final Set<URI> visitedUrls = new ConcurrentSkipListSet<>();
    
    // Results store
    private final ConcurrentHashMap<URI, PageResult> results = new ConcurrentHashMap<>();
    
    // Event listeners - CopyOnWrite vì listeners ít thay đổi
    private final CopyOnWriteArrayList<CrawlListener> listeners = new CopyOnWriteArrayList<>();
    
    private final HttpClient httpClient;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private volatile boolean shutdown = false;
    
    public ConcurrentWebCrawler() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())  // Java 21+
            .build();
    }
    
    public void addListener(CrawlListener listener) {
        listeners.add(listener);
    }
    
    public CompletableFuture<Void> start(int workerCount) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        
        CompletableFuture<?>[] futures = new CompletableFuture[workerCount];
        for (int i = 0; i < workerCount; i++) {
            futures[i] = CompletableFuture.runAsync(this::workerLoop, executor);
        }
        
        return CompletableFuture.allOf(futures);
    }
    
    private void workerLoop() {
        activeWorkers.incrementAndGet();
        try {
            while (!shutdown) {
                URI url = urlQueue.poll(1, TimeUnit.SECONDS);
                if (url == null) continue;
                
                // Atomic check-and-set
                if (!visitedUrls.add(url)) {
                    continue;  // Already visited
                }
                
                try {
                    HttpRequest request = HttpRequest.newBuilder(url)
                        .GET()
                        .timeout(Duration.ofSeconds(30))
                        .build();
                    
                    HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString()
                    );
                    
                    PageResult result = new PageResult(
                        url, 
                        response.statusCode(),
                        response.body().length(),
                        System.currentTimeMillis()
                    );
                    
                    // Concurrent put - thread-safe
                    results.put(url, result);
                    
                    // Notify listeners
                    listeners.forEach(l -> l.onPageCrawled(result));
                    
                } catch (Exception e) {
                    results.put(url, new PageResult(url, -1, 0, System.currentTimeMillis()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeWorkers.decrementAndGet();
        }
    }
    
    public boolean submitUrl(URI url) throws InterruptedException {
        return urlQueue.offer(url, 5, TimeUnit.SECONDS);  // Timeout để tránh block vô hạn
    }
    
    public void shutdown() {
        shutdown = true;
    }
    
    public int getQueueSize() {
        return urlQueue.size();
    }
    
    public int getVisitedCount() {
        return visitedUrls.size();
    }
    
    public int getResultCount() {
        return results.size();
    }
    
    // Records cho Java 16+
    record PageResult(URI url, int statusCode, int contentLength, long timestamp) {}
    
    @FunctionalInterface
    interface CrawlListener {
        void onPageCrawled(PageResult result);
    }
    
    // Demo
    public static void main(String[] args) throws Exception {
        ConcurrentWebCrawler crawler = new ConcurrentWebCrawler();
        
        // Add logging listener
        crawler.addListener(result -> 
            System.out.printf("Crawled: %s (status=%d, size=%d)%n",
                result.url(), result.statusCode(), result.contentLength())
        );
        
        // Start workers
        CompletableFuture<Void> completion = crawler.start(10);
        
        // Submit seed URLs
        String[] seeds = {
            "https://example.com",
            "https://httpbin.org/get",
            "https://jsonplaceholder.typicode.com/posts/1"
        };
        
        for (String url : seeds) {
            if (!crawler.submitUrl(URI.create(url))) {
                System.err.println("Queue full, dropped: " + url);
            }
        }
        
        // Wait và report
        Thread.sleep(10000);
        crawler.shutdown();
        
        System.out.printf("%nStats:%n");
        System.out.printf("- Queue remaining: %d%n", crawler.getQueueSize());
        System.out.printf("- Visited URLs: %d%n", crawler.getVisitedCount());
        System.out.printf("- Results stored: %d%n", crawler.getResultCount());
    }
}
