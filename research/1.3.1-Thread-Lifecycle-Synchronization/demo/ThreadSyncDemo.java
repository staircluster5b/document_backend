package demo;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

/**
 * Demo: Thread Lifecycle & Synchronization
 * Chạy với: javac demo/*.java && java demo.ThreadSyncDemo
 */
public class ThreadSyncDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Thread Lifecycle & Synchronization Demo ===\n");
        
        demoSynchronizedPerformance();
        demoVolatileVisibility();
        demoReentrantLock();
        demoReadWriteLock();
        demoStampedLock();
        demoDeadlockPrevention();
        
        System.out.println("\n=== All demos completed ===");
    }
    
    // 1. Synchronized Performance Demo
    static void demoSynchronizedPerformance() throws Exception {
        System.out.println("1. Synchronized Performance Test");
        System.out.println("--------------------------------");
        
        Counter syncCounter = new SynchronizedCounter();
        Counter lockCounter = new ReentrantLockCounter();
        
        int threads = 4;
        int iterations = 1_000_000;
        
        long syncTime = benchmarkCounter(syncCounter, threads, iterations);
        long lockTime = benchmarkCounter(lockCounter, threads, iterations);
        
        System.out.printf("Synchronized: %d ms%n", syncTime);
        System.out.printf("ReentrantLock: %d ms%n", lockTime);
        System.out.printf("Difference: %.1f%%%n%n", 
            ((double)(lockTime - syncTime) / syncTime) * 100);
    }
    
    // 2. Volatile Visibility Demo
    static void demoVolatileVisibility() throws InterruptedException {
        System.out.println("2. Volatile Visibility Demo");
        System.out.println("---------------------------");
        
        VolatileExample example = new VolatileExample();
        
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100);
                example.write();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        
        Thread reader = new Thread(example::read);
        
        reader.start();
        writer.start();
        
        reader.join();
        writer.join();
        System.out.println();
    }
    
    // 3. ReentrantLock Features Demo
    static void demoReentrantLock() throws Exception {
        System.out.println("3. ReentrantLock Features");
        System.out.println("-------------------------");
        
        ReentrantLock lock = new ReentrantLock();
        
        // Try-lock with timeout
        boolean acquired = lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (acquired) {
            try {
                System.out.println("Lock acquired with timeout");
                System.out.println("Hold count: " + lock.getHoldCount());
                System.out.println("Is fair: " + lock.isFair());
            } finally {
                lock.unlock();
            }
        }
        
        // Reentrancy demo
        lock.lock();
        try {
            nestedLockMethod(lock);
        } finally {
            lock.unlock();
        }
        System.out.println();
    }
    
    static void nestedLockMethod(ReentrantLock lock) {
        lock.lock(); // Reentrant - same thread can acquire again
        try {
            System.out.println("Reentrant lock acquired, hold count: " + lock.getHoldCount());
        } finally {
            lock.unlock();
        }
    }
    
    // 4. ReadWriteLock Demo
    static void demoReadWriteLock() throws Exception {
        System.out.println("4. ReadWriteLock Concurrency");
        System.out.println("----------------------------");
        
        ReadWriteData data = new ReadWriteData();
        int readerThreads = 10;
        int writerThreads = 2;
        
        java.util.concurrent.CountDownLatch latch = 
            new java.util.concurrent.CountDownLatch(readerThreads + writerThreads);
        
        long start = System.currentTimeMillis();
        
        // Readers
        for (int i = 0; i < readerThreads; i++) {
            new Thread(() -> {
                for (int j = 0; j < 1000; j++) {
                    data.read();
                }
                latch.countDown();
            }).start();
        }
        
        // Writers
        for (int i = 0; i < writerThreads; i++) {
            final int id = i;
            new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    data.write(id * 1000 + j);
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        long duration = System.currentTimeMillis() - start;
        
        System.out.printf("Completed with %d readers, %d writers in %d ms%n", 
            readerThreads, writerThreads, duration);
        System.out.printf("Total reads: %d, writes: %d%n%n", 
            data.getReadCount(), data.getWriteCount());
    }
    
    // 5. StampedLock Optimistic Reading
    static void demoStampedLock() {
        System.out.println("5. StampedLock Optimistic Reading");
        System.out.println("----------------------------------");
        
        StampedData data = new StampedData();
        
        // Writer thread
        new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                data.setX(i);
                data.setY(i * 2);
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
        
        // Optimistic reader
        int optimisticSuccess = 0;
        int fallbackCount = 0;
        
        for (int i = 0; i < 1000; i++) {
            double result = data.distanceFromOrigin();
            if (result >= 0) {
                optimisticSuccess++;
            } else {
                fallbackCount++;
            }
        }
        
        System.out.printf("Optimistic reads successful: %d%n", optimisticSuccess);
        System.out.printf("Fallback to read lock: %d%n%n", fallbackCount);
    }
    
    // 6. Deadlock Prevention Demo
    static void demoDeadlockPrevention() {
        System.out.println("6. Deadlock Prevention (Ordered Locking)");
        System.out.println("----------------------------------------");
        
        Account account1 = new Account(1000);
        Account account2 = new Account(2000);
        
        // Transfer using ordered locking
        Thread t1 = new Thread(() -> transfer(account1, account2, 100));
        Thread t2 = new Thread(() -> transfer(account2, account1, 200));
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        System.out.println("Transfer completed without deadlock");
        System.out.printf("Account 1: %d, Account 2: %d%n%n", 
            account1.getBalance(), account2.getBalance());
    }
    
    // Ordered locking - always lock smaller ID first
    static void transfer(Account from, Account to, int amount) {
        Account first = from.getId() < to.getId() ? from : to;
        Account second = from.getId() < to.getId() ? to : from;
        
        synchronized (first) {
            synchronized (second) {
                from.withdraw(amount);
                to.deposit(amount);
            }
        }
    }
    
    // Helper methods
    static long benchmarkCounter(Counter counter, int threads, int iterations) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);
        long start = System.currentTimeMillis();
        
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                for (int j = 0; j < iterations; j++) {
                    counter.increment();
                }
                latch.countDown();
            }).start();
        }
        
        latch.await();
        return System.currentTimeMillis() - start;
    }
}

// Counter interfaces and implementations
interface Counter {
    void increment();
    int get();
}

class SynchronizedCounter implements Counter {
    private int count;
    
    @Override
    public synchronized void increment() {
        count++;
    }
    
    @Override
    public synchronized int get() {
        return count;
    }
}

class ReentrantLockCounter implements Counter {
    private final ReentrantLock lock = new ReentrantLock();
    private int count;
    
    @Override
    public void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();
        }
    }
    
    @Override
    public int get() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }
}

// Volatile visibility demo
class VolatileExample {
    private volatile boolean ready = false;
    private int number;
    
    public void write() {
        number = 42;
        ready = true; // Happens-before guarantee
        System.out.println("Writer: number = " + number + ", ready = " + ready);
    }
    
    public void read() {
        while (!ready) {
            // Spin until ready
            Thread.yield();
        }
        System.out.println("Reader: number = " + number + ", ready = " + ready);
        // Without volatile: could see number = 0 (reordering!)
    }
}

// ReadWriteLock data structure
class ReadWriteData {
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private int value = 0;
    private final AtomicInteger readCount = new AtomicInteger(0);
    private final AtomicInteger writeCount = new AtomicInteger(0);
    
    public int read() {
        rwLock.readLock().lock();
        try {
            readCount.incrementAndGet();
            return value;
        } finally {
            rwLock.readLock().unlock();
        }
    }
    
    public void write(int newValue) {
        rwLock.writeLock().lock();
        try {
            value = newValue;
            writeCount.incrementAndGet();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
    
    public int getReadCount() {
        return readCount.get();
    }
    
    public int getWriteCount() {
        return writeCount.get();
    }
}

// StampedLock data structure
class StampedData {
    private final StampedLock lock = new StampedLock();
    private double x = 0;
    private double y = 0;
    
    public double distanceFromOrigin() {
        long stamp = lock.tryOptimisticRead();
        double currentX = x;
        double currentY = y;
        
        if (!lock.validate(stamp)) {
            // Fallback to read lock
            stamp = lock.readLock();
            try {
                currentX = x;
                currentY = y;
            } finally {
                lock.unlockRead(stamp);
            }
            return -1; // Indicate fallback was used
        }
        
        return Math.sqrt(currentX * currentX + currentY * currentY);
    }
    
    public void setX(double newX) {
        long stamp = lock.writeLock();
        try {
            x = newX;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
    
    public void setY(double newY) {
        long stamp = lock.writeLock();
        try {
            y = newY;
        } finally {
            lock.unlockWrite(stamp);
        }
    }
}

// Account for deadlock prevention demo
class Account {
    private static int idGenerator = 0;
    private final int id;
    private int balance;
    
    public Account(int initialBalance) {
        this.id = ++idGenerator;
        this.balance = initialBalance;
    }
    
    public int getId() {
        return id;
    }
    
    public synchronized int getBalance() {
        return balance;
    }
    
    public synchronized void withdraw(int amount) {
        balance -= amount;
    }
    
    public synchronized void deposit(int amount) {
        balance += amount;
    }
}
