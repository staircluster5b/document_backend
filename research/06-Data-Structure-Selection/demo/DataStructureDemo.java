package demo;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Demo: Data Structure Selection - ArrayList vs LinkedList, HashSet vs TreeSet
 * 
 * Chạy với: javac demo/DataStructureDemo.java && java demo.DataStructureDemo
 */
public class DataStructureDemo {
    
    private static final int SIZE = 100_000;
    
    public static void main(String[] args) {
        System.out.println("=== JAVA COLLECTIONS PERFORMANCE DEMO ===\n");
        
        demoListComparison();
        demoSetComparison();
        demoAntiPatterns();
        demoModernFeatures();
    }
    
    /**
     * Demo 1: So sánh ArrayList vs LinkedList
     */
    static void demoListComparison() {
        System.out.println("1. ARRAYLIST vs LINKEDLIST COMPARISON");
        System.out.println("   Size: " + SIZE + " elements\n");
        
        List<Integer> arrayList = new ArrayList<>(SIZE);
        List<Integer> linkedList = new LinkedList<>();
        
        // Populate
        for (int i = 0; i < SIZE; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }
        
        // Test 1: Random access
        System.out.println("   Test 1: Random Access (get(index))");
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < SIZE; i += 100) {
            sum += arrayList.get(i);
        }
        long arrayListTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        sum = 0;
        for (int i = 0; i < SIZE; i += 100) {
            sum += linkedList.get(i); // SLOW!
        }
        long linkedListTime = System.nanoTime() - start;
        
        System.out.println("      ArrayList:  " + formatTime(arrayListTime));
        System.out.println("      LinkedList: " + formatTime(linkedListTime));
        System.out.println("      Ratio: LinkedList is " + (linkedListTime / arrayListTime) + "x slower\n");
        
        // Test 2: Add at end
        System.out.println("   Test 2: Add at end");
        List<Integer> al = new ArrayList<>();
        List<Integer> ll = new LinkedList<>();
        
        start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) al.add(i);
        arrayListTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) ll.add(i);
        linkedListTime = System.nanoTime() - start;
        
        System.out.println("      ArrayList:  " + formatTime(arrayListTime));
        System.out.println("      LinkedList: " + formatTime(linkedListTime) + "\n");
        
        // Test 3: Insert at beginning
        System.out.println("   Test 3: Insert at beginning (10,000 ops)");
        al = new ArrayList<>();
        ll = new LinkedList<>();
        
        start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) al.add(0, i); // Expensive!
        arrayListTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) ll.add(0, i); // Fast!
        linkedListTime = System.nanoTime() - start;
        
        System.out.println("      ArrayList:  " + formatTime(arrayListTime));
        System.out.println("      LinkedList: " + formatTime(linkedListTime));
        System.out.println("      Winner: " + (linkedListTime < arrayListTime ? "LinkedList" : "ArrayList") + "\n");
    }
    
    /**
     * Demo 2: So sánh HashSet vs TreeSet
     */
    static void demoSetComparison() {
        System.out.println("2. HASHSET vs TREESET COMPARISON");
        System.out.println("   Size: " + SIZE + " elements\n");
        
        Set<Integer> hashSet = new HashSet<>();
        Set<Integer> treeSet = new TreeSet<>();
        
        // Test 1: Add operations
        System.out.println("   Test 1: Add " + SIZE + " elements");
        long start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) hashSet.add(i);
        long hashSetTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) treeSet.add(i);
        long treeSetTime = System.nanoTime() - start;
        
        System.out.println("      HashSet: " + formatTime(hashSetTime));
        System.out.println("      TreeSet: " + formatTime(treeSetTime));
        System.out.println("      Ratio: TreeSet is " + (treeSetTime / hashSetTime) + "x slower\n");
        
        // Test 2: Contains
        System.out.println("   Test 2: Contains (100,000 lookups)");
        start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) hashSet.contains(i);
        hashSetTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        for (int i = 0; i < SIZE; i++) treeSet.contains(i);
        treeSetTime = System.nanoTime() - start;
        
        System.out.println("      HashSet: " + formatTime(hashSetTime));
        System.out.println("      TreeSet: " + formatTime(treeSetTime) + "\n");
        
        // Test 3: Range query (TreeSet exclusive)
        System.out.println("   Test 3: Range Query - subSet(40000, 60000)");
        TreeSet<Integer> ts = (TreeSet<Integer>) treeSet;
        
        start = System.nanoTime();
        Set<Integer> range = ts.subSet(40_000, 60_000);
        int count = range.size();
        treeSetTime = System.nanoTime() - start;
        
        System.out.println("      TreeSet.subSet(): " + formatTime(treeSetTime));
        System.out.println("      Result count: " + count);
        System.out.println("      HashSet: CANNOT do range query efficiently!\n");
    }
    
    /**
     * Demo 3: Anti-patterns
     */
    static void demoAntiPatterns() {
        System.out.println("3. ANTI-PATTERNS DEMO\n");
        
        // Anti-pattern 1: LinkedList.get() in loop
        System.out.println("   Anti-pattern 1: LinkedList.get(i) in for loop");
        List<Integer> linkedList = new LinkedList<>();
        for (int i = 0; i < 10000; i++) linkedList.add(i);
        
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < linkedList.size(); i++) {
            sum += linkedList.get(i); // O(n) each = O(n²) total!
        }
        long badTime = System.nanoTime() - start;
        
        start = System.nanoTime();
        sum = 0;
        for (int val : linkedList) { // Iterator = O(n)
            sum += val;
        }
        long goodTime = System.nanoTime() - start;
        
        System.out.println("      Bad (get(i)):  " + formatTime(badTime));
        System.out.println("      Good (foreach):" + formatTime(goodTime));
        System.out.println("      Improvement: " + (badTime / goodTime) + "x faster\n");
        
        // Anti-pattern 2: Mutable key in HashSet
        System.out.println("   Anti-pattern 2: Mutable object as HashSet key");
        Set<MutableKey> set = new HashSet<>();
        MutableKey key = new MutableKey("Alice");
        set.add(key);
        System.out.println("      Added key with name='Alice'");
        System.out.println("      set.contains(key): " + set.contains(key));
        
        key.name = "Bob"; // Mutate!
        System.out.println("      After mutation to 'Bob':");
        System.out.println("      set.contains(key): " + set.contains(key) + " (!!!)\n");
    }
    
    /**
     * Demo 4: Java Modern Features
     */
    static void demoModernFeatures() {
        System.out.println("4. MODERN JAVA FEATURES (9+)\n");
        
        // Immutable collections
        System.out.println("   Immutable Collections:");
        List<String> immutableList = List.of("a", "b", "c");
        Set<String> immutableSet = Set.of("x", "y", "z");
        System.out.println("      List.of(): " + immutableList);
        System.out.println("      Set.of(): " + immutableSet);
        
        try {
            immutableList.add("d"); // Throws!
        } catch (UnsupportedOperationException e) {
            System.out.println("      immutableList.add() throws: " + e.getClass().getSimpleName());
        }
        
        // Copy of
        System.out.println("\n   Defensive Copy with copyOf():");
        List<String> original = new ArrayList<>();
        original.add("original");
        List<String> copy = List.copyOf(original);
        original.add("modified");
        System.out.println("      Original: " + original);
        System.out.println("      Copy: " + copy + " (unchanged)\n");
    }
    
    static String formatTime(long nanos) {
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return (nanos / 1_000) + " μs";
        if (nanos < 1_000_000_000) return (nanos / 1_000_000) + " ms";
        return (nanos / 1_000_000_000) + " s";
    }
    
    static class MutableKey {
        String name;
        
        MutableKey(String name) {
            this.name = name;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MutableKey that = (MutableKey) o;
            return Objects.equals(name, that.name);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
        
        @Override
        public String toString() {
            return "MutableKey{name='" + name + "'}";
        }
    }
}
