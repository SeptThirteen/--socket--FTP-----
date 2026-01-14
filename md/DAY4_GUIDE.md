# Java FTP 课设 - Day4 详细指导（初学者版）

## 今日目标
实现**文件下载功能（RETR 命令）**，让客户端能够从服务器下载文件：
- 理解文件传输的数据流
- 实现 `RETR <filename>` 命令
- 通过数据连接传输二进制文件内容
- 支持不同大小的文件传输（分块发送）
- 处理文件不存在、权限不足等错误

---

## 核心概念补充

### 文件传输 vs 文本传输

| 类型 | 数据格式 | 编码 | 示例 |
|------|---------|------|------|
| **文本传输** | 字符串 | UTF-8/GBK | 目录列表、命令 |
| **二进制传输** | 字节流 | 无需编码 | 图片、视频、可执行文件 |

### 分块传输原理

```
大文件 (10 MB)
    ↓
分成多个块 (每块 8192 字节)
    ↓
逐块发送 → [块1] → [块2] → ... → [块N]
    ↓
客户端逐块接收并写入文件
```

**为什么要分块？**
1. **内存限制**：不能一次性将整个文件加载到内存
2. **网络效率**：TCP 缓冲区有限，分块传输更高效
3. **进度追踪**：可以显示传输进度

### RETR 命令工作流程

```
客户端                              服务器
  |                                   |
  |--- PORT 127,0,0,1,p1,p2 --------->|
  |<----------------------------------|
  |    200 PORT OK                    |
  |                                   |
  |--- RETR test.txt ---------------->|
  |<----------------------------------|
  |    150 Opening data connection    |
  |                                   |
  |<--- 数据连接（服务器主动连接）-----|
  |    [传输文件内容，分块发送]        |
  |<----------------------------------|
  |    (数据连接关闭)                 |
  |                                   |
  |<----------------------------------|
  |    226 Transfer complete          |
```

---

## 项目结构变化

在 Day3 的基础上，修改以下文件：

```
基于socket的FTP设计与实现/
├── md/
│   ├── DAY1_GUIDE.md
│   ├── DAY2_GUIDE.md
│   ├── DAY3_GUIDE.md
│   └── DAY4_GUIDE.md                # 本文档
├── src/
│   ├── FtpServer.java               # 无需修改
│   ├── ClientSession.java           # 需要修改：增加 RETR 命令
│   ├── UserStore.java               # 无需修改
│   ├── PathValidator.java           # 无需修改
│   ├── DataConnection.java          # 需要修改：增加流式发送方法
│   └── SimpleFtpClient.java         # 需要修改：增加下载测试
├── data/                            # FTP 虚拟根目录
│   ├── test.txt                     # 测试文本文件
│   ├── image.jpg                    # 测试二进制文件（需要自己准备）
│   ├── public/
│   │   └── readme.txt
│   └── upload/
│       └── sample.txt
└── downloads/                       # 客户端下载目录（测试用）
```

---

## 代码实现详解

### 第 1 步：增强 `DataConnection.java` —— 增加流式发送方法

**目的**：支持大文件的分块传输，不将整个文件加载到内存。

**修改位置**：在现有 `DataConnection.java` 中添加新方法

**新增代码**：

```java
/**
 * 从输入流读取数据并发送（流式传输）
 * 用于文件下载，避免将整个文件加载到内存
 * 
 * @param inputStream 源输入流（如文件输入流）
 * @return 传输的总字节数
 * @throws IOException 如果传输失败
 */
public long sendFromStream(InputStream inputStream) throws IOException {
    if (outputStream == null) {
        throw new IOException("Data connection not established");
    }
    
    byte[] buffer = new byte[8192];  // 8KB 缓冲区
    int bytesRead;
    long totalBytes = 0;
    
    // 循环读取并发送
    while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytes += bytesRead;
    }
    
    outputStream.flush();
    
    System.out.println("[DataConnection] 已传输 " + totalBytes + " 字节");
    return totalBytes;
}

/**
 * 接收数据并写入输出流（流式接收）
 * 用于文件上传，避免将整个文件加载到内存
 * 
 * @param outputStream 目标输出流（如文件输出流）
 * @return 接收的总字节数
 * @throws IOException 如果接收失败
 */
public long receiveToStream(OutputStream outputStream) throws IOException {
    if (inputStream == null) {
        throw new IOException("Data connection not established");
    }
    
    byte[] buffer = new byte[8192];  // 8KB 缓冲区
    int bytesRead;
    long totalBytes = 0;
    
    // 循环接收并写入
    while ((bytesRead = inputStream.read(buffer)) != -1) {
        outputStream.write(buffer, 0, bytesRead);
        totalBytes += bytesRead;
    }
    
    outputStream.flush();
    
    System.out.println("[DataConnection] 已接收 " + totalBytes + " 字节");
    return totalBytes;
}
```

**关键点解释**：

1. **buffer = new byte[8192]**：
   - 8KB 是常用的缓冲区大小
   - 太小：频繁读写，效率低
   - 太大：占用内存多

2. **read(buffer)**：
   - 返回实际读取的字节数
   - 返回 -1 表示流结束
   - 可能读取少于 buffer.length 字节

3. **write(buffer, 0, bytesRead)**：
   - 参数 0：从 buffer 的第 0 个位置开始
   - 参数 bytesRead：写入实际读取的字节数
   - 不能用 `write(buffer)`，可能写入多余的旧数据

4. **totalBytes**：
   - 累计传输的字节数
   - 用于日志和统计

---

### 第 2 步：修改 `ClientSession.java` —— 增加 RETR 命令

**需要修改的内容**：
1. 在 `handleCommand()` 的 switch 中增加 `RETR` 的 case
2. 实现 `handleRetr(String filename)` 方法
3. 更新 `HELP` 命令

**修改代码**：

#### 2.1 在 switch 中添加 RETR case

在 `handleCommand()` 方法的 switch 语句中，`LIST` case 之后添加：

```java
case "RETR":
    if (!authenticated) {
        reply(530, "请先登录");
    } else {
        handleRetr(arg);
    }
    break;
```

#### 2.2 实现 handleRetr() 方法

在 `handleList()` 方法之后添加：

```java
/**
 * 处理 RETR 命令 - 下载文件
 * 
 * 命令格式：RETR <filename>
 * 
 * 工作流程：
 * 1. 检查是否已设置数据端口（PORT 命令）
 * 2. 验证文件路径安全性
 * 3. 检查文件是否存在且可读
 * 4. 发送 150 响应（即将打开数据连接）
 * 5. 建立数据连接
 * 6. 读取文件并分块发送
 * 7. 关闭数据连接
 * 8. 发送 226 响应（传输完成）
 * 
 * @param filename 要下载的文件名（相对于当前工作目录）
 */
private void handleRetr(String filename) throws IOException {
    // 1. 检查是否已设置数据端口
    if (dataAddress == null) {
        reply(425, "请先使用 PORT 命令");
        return;
    }
    
    // 2. 参数校验
    if (filename == null || filename.trim().isEmpty()) {
        reply(501, "RETR 命令需要参数");
        return;
    }
    
    filename = filename.trim();
    
    // 3. 解析文件路径（相对于当前工作目录）
    Path filePath;
    try {
        filePath = pathValidator.resolvePath(currentWorkingDir, filename);
    } catch (SecurityException e) {
        reply(550, "访问被拒绝: " + e.getMessage());
        return;
    } catch (IOException e) {
        reply(550, "无效的文件路径: " + e.getMessage());
        return;
    }
    
    // 4. 检查文件是否存在
    if (!Files.exists(filePath)) {
        reply(550, "文件不存在: " + filename);
        return;
    }
    
    // 5. 检查是否为普通文件（不是目录）
    if (!Files.isRegularFile(filePath)) {
        reply(550, filename + " 不是普通文件");
        return;
    }
    
    // 6. 检查文件是否可读
    if (!Files.isReadable(filePath)) {
        reply(550, "文件不可读: " + filename);
        return;
    }
    
    // 7. 获取文件大小（用于日志）
    long fileSize = Files.size(filePath);
    
    // 8. 发送"即将打开数据连接"的响应
    reply(150, "正在打开二进制模式数据连接以传输 " + filename + " (" + fileSize + " 字节)");
    
    // 9. 建立数据连接并传输文件
    DataConnection dataConn = new DataConnection();
    try {
        // 连接到客户端数据端口
        dataConn.connect(dataAddress);
        
        // 打开文件输入流
        try (InputStream fileInput = Files.newInputStream(filePath)) {
            // 流式传输文件内容
            long bytesTransferred = dataConn.sendFromStream(fileInput);
            
            System.out.println("[ClientSession] 文件 " + filename + " 传输完成: " + 
                             bytesTransferred + " 字节");
        }
        
    } catch (IOException e) {
        // 数据连接失败
        reply(426, "数据连接失败: " + e.getMessage());
        return;
    } finally {
        // 无论成功与否，都要关闭数据连接
        dataConn.close();
        // 清空数据地址（要求下次使用前重新设置 PORT）
        dataAddress = null;
    }
    
    // 10. 发送传输完成响应
    reply(226, "传输完成");
}
```

**关键点解释**：

1. **路径安全验证**：
   - 使用 `pathValidator.resolvePath()` 确保不越权
   - 捕获 `SecurityException` 拒绝恶意路径

2. **文件检查**：
   - `Files.exists()`：检查存在性
   - `Files.isRegularFile()`：排除目录
   - `Files.isReadable()`：检查读权限

3. **流式传输**：
   - `Files.newInputStream()`：打开文件流
   - `try-with-resources`：自动关闭文件流
   - `dataConn.sendFromStream()`：分块发送，不占用大量内存

4. **错误处理**：
   - 425：未设置数据端口
   - 501：缺少参数
   - 550：文件相关错误（不存在、不可读、越权）
   - 426：数据连接失败
   - 226：成功完成

#### 2.3 更新 HELP 命令

在 `handleHelp()` 方法中添加 RETR 的说明：

```java
private void handleHelp() throws IOException {
    reply(214, "支持的命令:");
    out.write("  user <用户名>  - 使用用户名登录\r\n");
    out.write("  pass <密码>  - 提供密码\r\n");
    out.write("  port h1,h2,h3,h4,p1,p2 - 设置数据端口\r\n");
    out.write("  list - 列出目录内容\r\n");
    out.write("  retr <文件名> - 下载文件\r\n");
    out.write("  quit - 断开连接\r\n");
    out.write("  cwd <目录>  - 更改当前目录\r\n");
    out.write("  pwd - 显示当前目录\r\n");
    out.write("  help - 显示此消息\r\n");
    out.flush();
}
```

---

### 第 3 步：修改 `SimpleFtpClient.java` —— 增加下载测试

**需要添加的内容**：
1. 实现 `retr(String filename, String savePath)` 方法
2. 在 `main()` 中增加下载测试

**新增代码**：

#### 3.1 添加 retr() 方法

在 `list()` 方法之后添加：

```java
/**
 * 下载文件
 * 
 * @param remoteFilename 服务器上的文件名（相对于当前工作目录）
 * @param localSavePath 本地保存路径（绝对路径）
 */
public void retr(String remoteFilename, String localSavePath) throws IOException {
    ServerSocket tempServerSocket = null;
    Socket dataSocket = null;
    
    try {
        // 1. 创建 ServerSocket 监听本地端口
        tempServerSocket = new ServerSocket(0);
        int dataPort = tempServerSocket.getLocalPort();
        
        System.out.println("\n[RETR 命令流程]");
        System.out.println("[客户端] 本地数据端口: " + dataPort);
        
        // 2. 发送 PORT 命令
        int p1 = dataPort / 256;
        int p2 = dataPort % 256;
        String portCmd = "PORT 127,0,0,1," + p1 + "," + p2;
        System.out.println("[客户端] 发送: " + portCmd);
        
        out.write(portCmd + "\r\n");
        out.flush();
        
        String portResponse = in.readLine();
        System.out.println("[服务器] " + portResponse);
        
        if (!portResponse.startsWith("200")) {
            System.err.println("[错误] PORT 命令失败");
            return;
        }
        
        // 3. 发送 RETR 命令
        System.out.println("[客户端] 发送: RETR " + remoteFilename);
        out.write("RETR " + remoteFilename + "\r\n");
        out.flush();
        
        // 4. 读取 150 响应
        String response150 = in.readLine();
        System.out.println("[服务器] " + response150);
        
        if (!response150.startsWith("150")) {
            System.err.println("[错误] RETR 命令响应异常: " + response150);
            // 尝试读取错误信息
            String errorResponse = in.readLine();
            if (errorResponse != null) {
                System.out.println("[服务器] " + errorResponse);
            }
            return;
        }
        
        // 5. 等待服务器的数据连接
        System.out.println("[客户端] 等待服务器的数据连接...");
        tempServerSocket.setSoTimeout(10000);
        dataSocket = tempServerSocket.accept();
        System.out.println("[客户端] ✓ 数据连接已建立");
        
        // 6. 接收文件数据并保存
        System.out.println("[客户端] 正在接收文件...");
        long startTime = System.currentTimeMillis();
        
        try (InputStream dataIn = dataSocket.getInputStream();
             FileOutputStream fileOut = new FileOutputStream(localSavePath)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalBytes = 0;
            
            while ((bytesRead = dataIn.read(buffer)) != -1) {
                fileOut.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                
                // 每接收 100KB 打印一次进度（可选）
                if (totalBytes % (100 * 1024) == 0) {
                    System.out.println("[客户端] 已接收: " + (totalBytes / 1024) + " KB");
                }
            }
            
            long endTime = System.currentTimeMillis();
            double seconds = (endTime - startTime) / 1000.0;
            double speed = (totalBytes / 1024.0) / seconds;  // KB/s
            
            System.out.println("[客户端] ✓ 文件接收完成");
            System.out.println("    - 文件大小: " + totalBytes + " 字节 (" + 
                             (totalBytes / 1024) + " KB)");
            System.out.println("    - 耗时: " + String.format("%.2f", seconds) + " 秒");
            System.out.println("    - 速度: " + String.format("%.2f", speed) + " KB/s");
            System.out.println("    - 保存到: " + localSavePath);
        }
        
        dataSocket.close();
        
        // 7. 读取 226 响应
        String response226 = in.readLine();
        System.out.println("[服务器] " + response226);
        
    } catch (SocketTimeoutException e) {
        System.err.println("[错误] 等待数据连接超时: " + e.getMessage());
    } catch (IOException e) {
        System.err.println("[错误] I/O 异常: " + e.getMessage());
        e.printStackTrace();
    } finally {
        if (dataSocket != null) {
            try {
                dataSocket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
        
        if (tempServerSocket != null && !tempServerSocket.isClosed()) {
            try {
                tempServerSocket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }
}
```

#### 3.2 在 main() 中添加下载测试

修改 `main()` 方法，增加下载测试：

```java
public static void main(String[] args) {
    SimpleFtpClient client = new SimpleFtpClient();
    try {
        System.out.println("========== FTP 客户端测试开始 ==========\n");
        
        client.connect("127.0.0.1", 2121);
        client.login("alice", "123456");
        client.pwd();
        
        System.out.println("\n[测试 1] 列出根目录");
        client.list();
        
        System.out.println("\n[测试 2] 下载 test.txt");
        client.retr("test.txt", "downloads/test_downloaded.txt");
        
        System.out.println("\n[测试 3] 切换到 public 目录并下载文件");
        client.cwd("public");
        client.list();
        client.retr("readme.txt", "downloads/readme_downloaded.txt");
        
        System.out.println("\n[测试 4] 测试下载不存在的文件");
        client.retr("nonexistent.txt", "downloads/nonexistent.txt");
        
        System.out.println("\n[测试完成] 断开连接");
        client.quit();
        
    } catch (Exception e) {
        System.err.println("[致命错误] " + e.getMessage());
        e.printStackTrace();
    }
}
```

---

## 准备测试环境

### 创建测试文件和目录

在项目根目录下执行：

```powershell
# 进入项目目录
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 创建 downloads 目录（客户端下载保存位置）
mkdir -Force downloads

# 创建测试文件（如果还没有）
"This is a test file for FTP download." | Out-File -Encoding UTF8 data\test.txt

# 创建一个较大的测试文件（可选）
$content = "A" * 1024  # 1KB
1..100 | ForEach-Object { $content } | Out-File -Encoding UTF8 data\large_file.txt
# 这会创建一个约 100KB 的文件

# 如果有图片文件，可以复制到 data 目录测试二进制文件
# Copy-Item "path\to\image.jpg" data\image.jpg
```

---

## 编译与运行

### 重新编译

```powershell
cd "c:\Users\Sept_thirteen\Desktop\计算机网络课设\基于socket 的FTP设计与实现"

# 删除旧的 bin 目录
Remove-Item -Recurse -Force bin -ErrorAction SilentlyContinue

# 编译所有 .java 文件
mkdir bin
javac -d bin -encoding UTF-8 src\*.java
```

### 运行测试

**终端 1 - 启动服务器：**
```powershell
cd bin
java data.FtpServer
```

**终端 2 - 运行测试客户端：**
```powershell
cd bin
java data.SimpleFtpClient
```

---

## 预期输出

### 服务器端输出

```
[FtpServer] FTP 服务器启动中...
[FtpServer] FTP 根目录: C:\...\data
[FtpServer] 用户表已初始化
[FtpServer] 线程池已创建，容量=32
[FtpServer] FTP 服务器启动成功，监听端口 2121
[FtpServer] 等待客户端连接...
[FtpServer] 客户端 #1 已连接: 127.0.0.1:xxxxx
[PathValidator] FTP 根目录设置为: C:\...\data
[ClientSession] null <- 220 简易 FTP 服务器已准备好
[ClientSession] alice <- 331 用户名正确，需要密码
[ClientSession] alice <- 230 用户 alice 已登录
[ClientSession] alice <- 257 "/" 是当前目录
[ClientSession] 客户端数据端口设置为: /127.0.0.1:xxxxx
[ClientSession] alice <- 200 PORT 命令执行成功
[ClientSession] alice <- 150 正在打开 ASCII 模式数据连接以获取文件列表
[DataConnection] 正在连接到客户端数据端口: /127.0.0.1:xxxxx
[DataConnection] 数据连接已建立
[ClientSession] 已发送目录列表，共 xxx 字节
[DataConnection] 数据连接已关闭
[ClientSession] alice <- 226 传输完成
[ClientSession] 客户端数据端口设置为: /127.0.0.1:xxxxx
[ClientSession] alice <- 200 PORT 命令执行成功
[ClientSession] alice <- 150 正在打开二进制模式数据连接以传输 test.txt (38 字节)
[DataConnection] 正在连接到客户端数据端口: /127.0.0.1:xxxxx
[DataConnection] 数据连接已建立
[DataConnection] 已传输 38 字节
[ClientSession] 文件 test.txt 传输完成: 38 字节
[DataConnection] 数据连接已关闭
[ClientSession] alice <- 226 传输完成
```

### 客户端输出

```
========== FTP 客户端测试开始 ==========

[客户端] 连接到 127.0.0.1:2121
[服务器] 220 简易 FTP 服务器已准备好

[登录流程]
[客户端] 发送: USER alice
[服务器] 331 用户名正确，需要密码
[客户端] 发送: PASS 123456
[服务器] 230 用户 alice 已登录

[客户端] 发送: PWD
[服务器] 257 "/" 是当前目录

[测试 1] 列出根目录

[LIST 命令流程]
[客户端] 本地数据端口: xxxxx
...
===== 目录列表 =====
  public/
  upload/
  test.txt (38 字节)
  large_file.txt (102400 字节)
共 4 项
====================
[服务器] 226 传输完成

[测试 2] 下载 test.txt

[RETR 命令流程]
[客户端] 本地数据端口: xxxxx
[客户端] 发送: PORT 127,0,0,1,xxx,xxx
[服务器] 200 PORT 命令执行成功
[客户端] 发送: RETR test.txt
[服务器] 150 正在打开二进制模式数据连接以传输 test.txt (38 字节)
[客户端] 等待服务器的数据连接...
[客户端] ✓ 数据连接已建立
[客户端] 正在接收文件...
[客户端] ✓ 文件接收完成
    - 文件大小: 38 字节 (0 KB)
    - 耗时: 0.05 秒
    - 速度: 0.76 KB/s
    - 保存到: downloads/test_downloaded.txt
[服务器] 226 传输完成
```

---

## 测试场景

### 测试 1：下载小文本文件

**步骤**：
1. 下载 `test.txt`（约 38 字节）
2. 检查 `downloads/test_downloaded.txt` 是否存在
3. 对比内容是否一致

**验证**：
```powershell
# 比较原文件和下载的文件
$original = Get-Content data\test.txt
$downloaded = Get-Content downloads\test_downloaded.txt
if ($original -eq $downloaded) {
    Write-Host "✓ 文件内容一致" -ForegroundColor Green
} else {
    Write-Host "✗ 文件内容不一致" -ForegroundColor Red
}
```

### 测试 2：下载较大文件

**步骤**：
1. 下载 `large_file.txt`（约 100KB）
2. 观察进度输出
3. 检查文件大小

**验证**：
```powershell
$originalSize = (Get-Item data\large_file.txt).Length
$downloadedSize = (Get-Item downloads\large_file_downloaded.txt).Length

Write-Host "原文件大小: $originalSize 字节"
Write-Host "下载文件大小: $downloadedSize 字节"

if ($originalSize -eq $downloadedSize) {
    Write-Host "✓ 文件大小一致" -ForegroundColor Green
} else {
    Write-Host "✗ 文件大小不一致" -ForegroundColor Red
}
```

### 测试 3：下载二进制文件（图片）

**准备**：
```powershell
# 复制一张图片到 data 目录
Copy-Item "C:\path\to\image.jpg" data\image.jpg
```

**客户端代码**：
```java
client.retr("image.jpg", "downloads/image_downloaded.jpg");
```

**验证**：
- 用图片查看器打开 `downloads/image_downloaded.jpg`
- 应该能正常显示，与原图一致

### 测试 4：错误处理

**场景 4.1：下载不存在的文件**
```java
client.retr("nonexistent.txt", "downloads/nonexistent.txt");
```

**预期输出**：
```
[客户端] 发送: RETR nonexistent.txt
[服务器] 550 文件不存在: nonexistent.txt
[错误] RETR 命令响应异常: 550 文件不存在: nonexistent.txt
```

**场景 4.2：尝试下载目录**
```java
client.retr("public", "downloads/public");
```

**预期输出**：
```
[服务器] 550 public 不是普通文件
```

**场景 4.3：未登录时下载**
```java
// 不调用 login()
client.retr("test.txt", "downloads/test.txt");
```

**预期输出**：
```
[服务器] 530 请先登录
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `550 文件不存在` | 文件名拼写错误或文件确实不存在 | 检查文件名、先用 LIST 确认 |
| `550 访问被拒绝` | 尝试越权访问根目录外的文件 | 检查路径，不要使用 `../..` |
| `426 数据连接失败` | 客户端未监听或网络问题 | 检查客户端 ServerSocket 是否正常 |
| 文件大小为 0 | 数据连接关闭过早 | 检查是否在 flush() 后立即关闭 |
| 文件内容不完整 | 缓冲区未 flush 或网络中断 | 确保 `outputStream.flush()` |
| 内存溢出 | 大文件一次性加载到内存 | 使用流式传输，分块读写 |

---

## Debug 技巧

### 在关键位置加日志

**在 DataConnection.sendFromStream() 中**：

```java
System.out.println("[DEBUG] 开始流式传输");
while ((bytesRead = inputStream.read(buffer)) != -1) {
    System.out.println("[DEBUG] 读取 " + bytesRead + " 字节");
    outputStream.write(buffer, 0, bytesRead);
    totalBytes += bytesRead;
}
System.out.println("[DEBUG] 传输完成，总计 " + totalBytes + " 字节");
```

### 验证文件完整性

```powershell
# 使用 PowerShell 计算文件哈希
$originalHash = Get-FileHash data\test.txt -Algorithm MD5
$downloadedHash = Get-FileHash downloads\test_downloaded.txt -Algorithm MD5

if ($originalHash.Hash -eq $downloadedHash.Hash) {
    Write-Host "✓ 文件完全一致（MD5 匹配）" -ForegroundColor Green
} else {
    Write-Host "✗ 文件已损坏（MD5 不匹配）" -ForegroundColor Red
}
```

---

## 检查清单

完成 Day4，你应该能做到：

- [ ] `DataConnection.java` 增加了 `sendFromStream()` 方法
- [ ] `ClientSession.java` 增加了 `handleRetr()` 方法
- [ ] switch 语句中添加了 RETR case
- [ ] HELP 命令显示 RETR 说明
- [ ] `SimpleFtpClient.java` 增加了 `retr()` 方法
- [ ] 服务器启动正常
- [ ] 客户端可以下载小文本文件
- [ ] 客户端可以下载较大文件（100KB+）
- [ ] 下载的文件内容与原文件一致（MD5 匹配）
- [ ] 可以下载二进制文件（图片）
- [ ] 下载不存在的文件收到 550 错误
- [ ] 尝试下载目录收到 550 错误
- [ ] 可以在不同目录下下载文件
- [ ] 传输速度显示正常

---

## 核心概念回顾

### 文件传输的完整流程

```
1. PORT 设置数据端口
   ↓
2. RETR 请求文件
   ↓
3. 150 即将打开连接
   ↓
4. 建立数据连接
   ↓
5. 服务器读取文件 → 分块 → 发送
   ↓
6. 客户端接收 → 分块 → 写入文件
   ↓
7. 关闭数据连接
   ↓
8. 226 传输完成
```

### 分块传输的优势

1. **内存高效**：不需要一次性加载整个文件
2. **支持大文件**：可以传输 GB 级文件而不溢出
3. **可中断恢复**：理论上可以实现断点续传（高级功能）
4. **实时进度**：可以显示传输进度

### 二进制 vs 文本模式

| 模式 | FTP 术语 | Java 实现 | 适用场景 |
|------|---------|----------|---------|
| **ASCII** | TYPE A | 字符流（Reader/Writer） | 纯文本文件 |
| **Binary** | TYPE I | 字节流（InputStream/OutputStream） | 图片、视频、压缩包 |

**本项目使用 Binary 模式**（更通用）。

---

## 下一步预告（Day5）

- 实现 `STOR <filename>` 命令：上传文件
- 客户端读取本地文件并发送到服务器
- 处理文件覆盖策略（覆盖 vs 拒绝）
- 处理磁盘空间不足等错误

---

## 参考资源

- [Java InputStream/OutputStream](https://docs.oracle.com/javase/8/docs/api/java/io/InputStream.html)
- [Java NIO Files.newInputStream](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html#newInputStream-java.nio.file.Path-java.nio.file.OpenOption...-)
- [FTP 协议 - RETR 命令](https://tools.ietf.org/html/rfc959#section-4.1.3)

---

## 常见问题（FAQ）

**Q：为什么要用字节流而不是字符流？**  
A：字节流可以处理任何类型的文件（文本、图片、视频）。字符流只能处理文本，且可能因为编码问题损坏二进制文件。

**Q：8192 字节的缓冲区是怎么确定的？**  
A：这是经验值。太小（如 512）会频繁 I/O，太大（如 1MB）占用内存。8KB 是常见的折中选择。

**Q：如果网络中断，文件会损坏吗？**  
A：会。当前实现没有错误恢复机制。高级功能可以实现断点续传（需要客户端和服务器协商偏移量）。

**Q：能否显示实时进度条？**  
A：可以。在接收循环中计算百分比：
```java
int progress = (int)((totalBytes * 100) / expectedSize);
System.out.print("\r下载进度: " + progress + "%");
```

**Q：Files.newInputStream() 和 FileInputStream 有什么区别？**  
A：`Files.newInputStream()` 是 NIO.2 API，更现代，支持更多选项。`FileInputStream` 是传统 API。功能类似，推荐用 NIO.2。

**Q：如何支持断点续传？**  
A：需要实现 REST 命令（FTP 扩展），客户端告诉服务器从哪个偏移量开始发送。本课设不要求。

---

**恭喜你完成 Day4！你已经掌握了 FTP 文件下载的核心技术。** 🎉
