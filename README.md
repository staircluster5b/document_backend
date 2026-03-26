# 🚀 Lộ Trình Nghiên Cứu: Từ Java Core Đến Senior Backend Chuyên Nghiệp

> **Mục tiêu:** Làm chủ ngôn ngữ, thấu hiểu kiến trúc hệ thống, quản trị rủi ro và tối ưu hóa hiệu năng.

---

## ☕ PHẦN 1: JAVA CORE MASTERY (NỀN TẢNG CHUYÊN SÂU)
*Tập trung vào cách ngôn ngữ vận hành dưới "nắp máy" thay vì chỉ sử dụng cú pháp.*

### 1.1 Quản lý Bộ nhớ & JVM (Java Virtual Machine)
- [x] **Tìm hiểu cấu trúc bộ nhớ JVM:** Phân biệt rõ Heap, Stack, Metaspace (trước đây là PermGen).
- [x] **Cơ chế Garbage Collection (GC):** Nghiên cứu các thuật toán G1, ZGC, Shenandoah. Hiểu khi nào thì xảy ra "Stop-the-world".
- [x] **JVM Tuning:** Cách sử dụng các tham số `-Xms`, `-Xmx`, `-XX:MaxMetaspaceSize` để tối ưu ứng dụng.
- [ ] **Memory Leak:** Nhận diện rủi ro rò rỉ bộ nhớ (Static references, Unclosed resources) và cách dùng công cụ Profiler (VisualVM, JProfiler).

### 1.2 Deep Dive into Collections Framework
- [ ] **Mã nguồn (Source code) phân tích:** Xem cách `HashMap` xử lý Collision (Hash bucket, Treeify) và `ConcurrentHashMap` (Lock stripping).
- [ ] **Lựa chọn cấu trúc dữ liệu:** Khi nào dùng `ArrayList` vs `LinkedList`, `HashSet` vs `TreeSet` dựa trên độ phức tạp thuật toán (Big O).
- [ ] **Fail-fast vs Fail-safe:** Phân biệt cơ chế Iteration trong môi trường đa luồng.
