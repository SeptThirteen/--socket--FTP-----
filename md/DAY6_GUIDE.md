# Java FTP 课设 - Day6 详细指导（初学者版）

## 今日目标
完善 FTP 服务器功能并提升鲁棒性：
- 实现 `DELE <filename>` 命令：删除文件
- 完善 FTP 响应码系统
- 增加超时保护机制
- 测试多客户端并发连接
- 压力测试（多文件、大文件、异常场景）
- 添加日志系统和统计信息

---

## 核心概念补充

### FTP 响应码体系

FTP 使用**三位数字响应码**表示命令执行结果：

| 首位数字 | 含义 | 示例 |
|---------|------|------|
| **1xx** | 初步肯定响应（等待下一步） | 150 Opening data connection |
| **2xx** | 完全肯定响应（成功） | 226 Transfer complete |
| **3xx** | 中间肯定响应（需要更多信息） | 331 Password required |
| **4xx** | 暂时否定响应（稍后可重试） | 425 Can't open data connection |
| **5xx** | 永久否定响应（命令错误） | 550 File not found |

**第二位数字**：

| 数字 | 含义 | 示例 |
|------|------|------|
| **x0x** | 语法错误 | 500 Syntax error |
| **x1x** | 信息回复 | 211 System status |
| **x2x** | 连接相关 | 220 Service ready |
| **x3x** | 认证和账户 | 331 Password required |
| **x4x** | 未定义 | - |
| **x5x** | 文件系统 | 550 File not found |

### 常用 FTP 响应码清单

| 代码 | 含义 | 使用场景 |
|------|------|---------|
| 150 | 即将打开数据连接 | LIST/RETR/STOR 开始前 |
| 200 | 命令执行成功 | PORT/CWD 成功后 |
| 220 | 服务就绪 | 客户端连接时欢迎消息 |
| 226 | 传输完成 | LIST/RETR/STOR 成功后 |
| 230 | 用户已登录 | PASS 成功后 |
| 250 | 文件操作成功 | DELE 成功后 |
| 257 | 路径名创建成功 | PWD 返回当前目录 |
| 331 | 需要密码 | USER 成功后 |
| 425 | 无法打开数据连接 | 未调用 PORT |
| 426 | 连接中止，数据连接失败 | 数据传输中断 |
| 450 | 文件操作失败（临时） | 文件正在被使用 |
| 500 | 语法错误 | 命令拼写错误 |
| 501 | 参数语法错误 | 缺少必需参数 |
| 502 | 命令未实现 | 不支持的命令 |
| 530 | 未登录 | 执行命令前未认证 |
| 550 | 文件不可用 | 文件不存在、无权限 |
| 553 | 文件名不合法 | 非法字符、越权路径 |

### 超时机制

**为什么需要超时？**
- **控制连接超时**：防止客户端长时间占用连接而不操作
- **数据连接超时**：防止网络故障导致数据传输无限期等待

| 超时类型 | 推荐值 | 作用 |
|---------|-------|------|
| 控制连接空闲超时 | 300 秒（5 分钟） | 客户端无操作则断开 |
| 数据连接建立超时 | 30 秒 | 等待数据连接建立 |
| Socket 读取超时 | 60 秒 | 读取命令/数据超时 |

---

## 项目结构变化

在 Day5 的基础上，修改以下文件：

```
基于socket的FTP设计与实现/
├── md/
│   ├── DAY1_GUIDE.md
│   ├── DAY2_GUIDE.md
│   ├── DAY3_GUIDE.md
│   ├── DAY4_GUIDE.md
│   ├── DAY5_GUIDE.md
│   └── DAY6_GUIDE.md                # 本文档
├── src/
│   ├── FtpServer.java               # 无需修改
│   ├── ClientSession.java           # 需要修改：增加 DELE、超时、完善响应码
│   ├── UserStore.java               # 无需修改
│   ├── PathValidator.java           # 无需修改
│   ├── DataConnection.java          # 需要修改：增加超时设置
│   ├── SimpleFtpClient.java         # 需要修改：增加 DELE 测试
│   └── SessionStatistics.java       # 新增：会话统计类
├── data/
├── downloads/
└── uploads/
```

---

## 代码实现详解

### 第 1 步：修改 `ClientSession.java` —— 增加 DELE 命令

#### 1.1 在 switch 中添加 DELE case

在 `handleCommand()` 方法的 switch 语句中，`STOR` case 之后添加：

```java
case "DELE":
    if (!authenticated) {
        reply(530, "请先登录");
    } else {
        handleDele(arg);
    }
    break;
```

#### 1.2 实现 handleDele() 方法

在 `handleStor()` 方法之后添加：

```java
/**
 * 处理 DELE 命令 - 删除文件
 * 
 * 命令格式：DELE <filename>
 * 
 * 工作流程：
 * 1. 验证文件路径安全性
 * 2. 检查文件是否存在且为普通文件
 * 3. 尝试删除文件
 * 4. 返回成功（250）或失败（450/550）响应
 * 
 * @param filename 要删除的文件名（相对于当前工作目录）
 */
private void handleDele(String filename) throws IOException {
    // 1. 参数校验
    if (filename == null || filename.trim().isEmpty()) {
        reply(501, "DELE 命令需要参数");
        return;
    }
    
    filename = filename.trim();
    
    // 2. 解析文件路径（相对于当前工作目录）
    Path filePath;
    try {
        filePath = pathValidator.resolvePath(currentWorkingDir, filename);
    } catch (SecurityException e) {
        reply(550, "访问被拒绝: " + e.getMessage());
        return;
    } catch (IOException e) {
        reply(553, "无效的文件路径: " + e.getMessage());
        return;
    }
    
    // 3. 检查文件是否存在
    if (!Files.exists(filePath)) {
        reply(550, "文件不存在: " + filename);
        return;
    }
    
    // 4. 检查是否为普通文件（不允许删除目录）
    if (!Files.isRegularFile(filePath)) {
        reply(550, filename + " 不是普通文件（不能删除目录）");
        return;
    }
    
    // 5. 尝试删除文件
    try {
        Files.delete(filePath);
        System.out.println("[ClientSession] 文件已删除: " + filename);
        reply(250, "文件 " + filename + " 已删除");
    } catch (IOException e) {
        // 删除失败（可能是文件被占用、权限不足）
        System.err.println("[ClientSession] 删除文件失败: " + e.getMessage());
        reply(450, "无法删除文件: " + e.getMessage());
    }
}
```

**关键点解释**：

1. **路径安全验证**：
   - 使用 `pathValidator.resolvePath()` 防止越权删除

2. **文件类型检查**：
   - `Files.isRegularFile()`：只允许删除文件，不允许删除目录

3. **删除操作**：
   - `Files.delete()`：删除文件，失败抛出异常

4. **响应码**：
   - 250：成功删除
   - 450：临时错误（文件被占用）
   - 550：永久错误（文件不存在、越权）
   - 553：文件名不合法

---

### 第 2 步：增加超时保护 —— 修改 `ClientSession.java`

#### 2.1 添加常量和成员变量

在 `ClientSession` 类的顶部添加：

```java
// 超时设置（毫秒）
private static final int SOCKET_TIMEOUT = 60000;      // 60 秒
private static final int IDLE_TIMEOUT = 300000;       // 5 分钟

// 会话统计
private long lastActiveTime;  // 上次活动时间
private int commandCount;     // 执行的命令数
```

#### 2.2 在构造方法中设置超时

修改构造方法，添加超时设置和统计初始化：

```java
public ClientSession(Socket clientSocket, Path rootDirectory) throws IOException {
    this.clientSocket = clientSocket;
    
    // 设置 Socket 读取超时
    clientSocket.setSoTimeout(SOCKET_TIMEOUT);
    
    this.in = new BufferedReader(
        new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8)
    );
    this.out = new BufferedWriter(
        new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8)
    );
    
    this.pathValidator = new PathValidator(rootDirectory);
    this.currentWorkingDir = Paths.get("/");
    this.authenticated = false;
    this.currentUser = null;
    this.dataAddress = null;
    
    // 初始化统计
    this.lastActiveTime = System.currentTimeMillis();
    this.commandCount = 0;
}
```

#### 2.3 在 run() 方法中检查空闲超时

修改 `run()` 方法，增加空闲超时检查：

```java
@Override
public void run() {
    try {
        // 发送欢迎消息
        reply(220, "简易 FTP 服务器已准备好");
        
        // 命令处理循环
        String line;
        while ((line = in.readLine()) != null) {
            // 更新活动时间
            lastActiveTime = System.currentTimeMillis();
            commandCount++;
            
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            
            // 解析命令
            String[] parts = line.split(" ", 2);
            String command = parts[0].toUpperCase();
            String arg = (parts.length > 1) ? parts[1] : null;
            
            System.out.println("[ClientSession] " + currentUser + " <- " + line);
            
            // 处理命令
            handleCommand(command, arg);
            
            // 检查空闲超时（可选，在每个命令后检查）
            long idleTime = System.currentTimeMillis() - lastActiveTime;
            if (idleTime > IDLE_TIMEOUT) {
                reply(421, "空闲超时，连接关闭");
                break;
            }
        }
        
    } catch (SocketTimeoutException e) {
        // Socket 读取超时
        System.out.println("[ClientSession] 客户端超时: " + e.getMessage());
        try {
            reply(421, "连接超时");
        } catch (IOException ioEx) {
            // 忽略
        }
    } catch (IOException e) {
        System.err.println("[ClientSession] I/O 错误: " + e.getMessage());
    } finally {
        // 关闭连接
        try {
            clientSocket.close();
            System.out.println("[ClientSession] 客户端连接已关闭（用户: " + currentUser + 
                             ", 命令数: " + commandCount + ")");
        } catch (IOException e) {
            System.err.println("[ClientSession] 关闭连接失败: " + e.getMessage());
        }
    }
}
```

**关键点解释**：

1. **setSoTimeout()**：
   - 设置 Socket 读取超时
   - 超时后抛出 `SocketTimeoutException`

2. **空闲超时检查**：
   - 每个命令执行后更新 `lastActiveTime`
   - 可选：检查距上次活动的时间，超过阈值则断开

3. **统计信息**：
   - `commandCount`：累计执行的命令数
   - 用于日志和调试

---

### 第 3 步：修改 `DataConnection.java` —— 增加超时设置

在 `connect()` 方法中添加超时设置：

```java
public void connect(InetSocketAddress serverAddress) throws IOException {
    System.out.println("[DataConnection] 正在连接到客户端数据端口: " + serverAddress);
    
    socket = new Socket();
    
    // 设置连接超时（30 秒）
    socket.connect(serverAddress, 30000);
    
    // 设置读写超时（60 秒）
    socket.setSoTimeout(60000);
    
    inputStream = socket.getInputStream();
    outputStream = socket.getOutputStream();
    
    System.out.println("[DataConnection] 数据连接已建立");
}
```

**关键点解释**：

1. **connect(address, timeout)**：
   - 第二个参数是连接超时（毫秒）
   - 超时后抛出 `SocketTimeoutException`

2. **setSoTimeout()**：
   - 设置数据读写超时
   - 防止网络故障导致无限期等待

---

### 第 4 步：完善响应码 —— 修改 `ClientSession.java`

#### 4.1 更新 handleCommand() —— 处理未知命令

在 `handleCommand()` 方法的 switch 语句末尾，修改 default case：

```java
default:
    reply(502, "不支持的命令: " + command);
    break;
```

#### 4.2 在各命令中添加更详细的错误信息

**示例 1：handlePort() 中的参数错误**

```java
// 在 handlePort() 方法中，解析失败时：
catch (Exception e) {
    reply(501, "PORT 命令参数格式错误");
    return;
}
```

**示例 2：handleCwd() 中的路径错误**

```java
// 在 handleCwd() 方法中，路径无效时：
catch (SecurityException e) {
    reply(550, "访问被拒绝: " + e.getMessage());
    return;
}
```

#### 4.3 更新 HELP 命令

```java
private void handleHelp() throws IOException {
    reply(214, "支持的命令:");
    out.write("  user <用户名>  - 使用用户名登录\r\n");
    out.write("  pass <密码>  - 提供密码\r\n");
    out.write("  port h1,h2,h3,h4,p1,p2 - 设置数据端口\r\n");
    out.write("  list - 列出目录内容\r\n");
    out.write("  retr <文件名> - 下载文件\r\n");
    out.write("  stor <文件名> - 上传文件\r\n");
    out.write("  dele <文件名> - 删除文件\r\n");
    out.write("  quit - 断开连接\r\n");
    out.write("  cwd <目录>  - 更改当前目录\r\n");
    out.write("  pwd - 显示当前目录\r\n");
    out.write("  help - 显示此消息\r\n");
    out.flush();
}
```

---

### 第 5 步：创建 `SessionStatistics.java` —— 会话统计类（可选）

**新建文件** `src/SessionStatistics.java`：

```java
package data;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话统计类（线程安全）
 * 
 * 用于记录服务器的统计信息：
 * - 总连接数
 * - 当前活跃连接数
 * - 传输的总字节数
 * - 执行的命令数
 */
public class SessionStatistics {
    private static final AtomicInteger totalConnections = new AtomicInteger(0);
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final AtomicLong totalBytesTransferred = new AtomicLong(0);
    private static final AtomicInteger totalCommands = new AtomicInteger(0);
    
    /**
     * 记录新连接
     */
    public static void onClientConnect() {
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
    }
    
    /**
     * 记录连接断开
     */
    public static void onClientDisconnect() {
        activeConnections.decrementAndGet();
    }
    
    /**
     * 记录传输的字节数
     */
    public static void addBytesTransferred(long bytes) {
        totalBytesTransferred.addAndGet(bytes);
    }
    
    /**
     * 记录执行的命令
     */
    public static void incrementCommandCount() {
        totalCommands.incrementAndGet();
    }
    
    /**
     * 获取统计信息
     */
    public static String getStatistics() {
        return String.format(
            "总连接数: %d, 活跃连接: %d, 传输字节: %d, 总命令数: %d",
            totalConnections.get(),
            activeConnections.get(),
            totalBytesTransferred.get(),
            totalCommands.get()
        );
    }
}
```

**在 `ClientSession.java` 中调用**：

```java
// 在构造方法中
SessionStatistics.onClientConnect();

// 在 run() 方法的 finally 中
SessionStatistics.onClientDisconnect();

// 在 handleCommand() 中
SessionStatistics.incrementCommandCount();

// 在 DataConnection 传输后（可选）
SessionStatistics.addBytesTransferred(bytesTransferred);
```

**在 `FtpServer.java` 中添加定时输出**（可选）：

```java
// 在 main() 方法中，启动服务器后
Timer timer = new Timer(true);
timer.scheduleAtFixedRate(new TimerTask() {
    @Override
    public void run() {
        System.out.println("[统计] " + SessionStatistics.getStatistics());
    }
}, 60000, 60000);  // 每分钟输出一次
```

**需要添加导入**：

```java
import java.util.Timer;
import java.util.TimerTask;
```

---

### 第 6 步：修改 `SimpleFtpClient.java` —— 增加 DELE 测试

#### 6.1 添加 dele() 方法

在 `stor()` 方法之后添加：

```java
/**
 * 删除文件
 * 
 * @param remoteFilename 服务器上的文件名（相对于当前工作目录）
 */
public void dele(String remoteFilename) throws IOException {
    System.out.println("\n[DELE 命令流程]");
    System.out.println("[客户端] 发送: DELE " + remoteFilename);
    
    out.write("DELE " + remoteFilename + "\r\n");
    out.flush();
    
    String response = in.readLine();
    System.out.println("[服务器] " + response);
    
    if (response.startsWith("250")) {
        System.out.println("[客户端] ✓ 文件删除成功");
    } else {
        System.err.println("[客户端] ✗ 文件删除失败");
    }
}
```

#### 6.2 在 main() 中添加 DELE 测试

修改 `main()` 方法，增加删除测试：

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
        
        System.out.println("\n[测试 2] 上传测试文件");
        client.stor("uploads/client_file.txt", "test_delete.txt");
        
        System.out.println("\n[测试 3] 验证上传：列出根目录");
        client.list();
        
        System.out.println("\n[测试 4] 删除刚上传的文件");
        client.dele("test_delete.txt");
        
        System.out.println("\n[测试 5] 验证删除：列出根目录");
        client.list();
        
        System.out.println("\n[测试 6] 尝试删除不存在的文件");
        client.dele("nonexistent.txt");
        
        System.out.println("\n[测试 7] 尝试删除目录（应该失败）");
        client.dele("public");
        
        System.out.println("\n[测试完成] 断开连接");
        client.quit();
        
    } catch (Exception e) {
        System.err.println("[致命错误] " + e.getMessage());
        e.printStackTrace();
    }
}
```

---

## 并发测试

### 测试场景 1：多客户端同时连接

**创建测试脚本** `test_concurrent.ps1`：

```powershell
# 启动 3 个客户端实例
1..3 | ForEach-Object {
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd bin; java data.SimpleFtpClient"
    Start-Sleep -Milliseconds 500
}
```

**预期结果**：
- 服务器日志显示 3 个客户端连接
- 每个客户端独立执行命令，不互相干扰
- 服务器统计信息显示 "活跃连接: 3"

### 测试场景 2：同时上传/下载

**手动测试**：
1. 打开 3 个客户端终端
2. 客户端 1：上传大文件
3. 客户端 2：下载大文件
4. 客户端 3：列出目录、删除文件

**预期结果**：
- 操作互不干扰
- 传输速度正常
- 无死锁或资源冲突

---

## 压力测试

### 测试场景 1：大文件传输

**创建 10MB 测试文件**：

```powershell
# 创建 10MB 文件
$content = "A" * 1024
1..10240 | ForEach-Object { $content } | Out-File -Encoding UTF8 uploads\10mb_test.txt
```

**测试步骤**：
1. 上传 10MB 文件
2. 下载 10MB 文件
3. 验证完整性（MD5）

**验证**：
```powershell
$originalHash = Get-FileHash uploads\10mb_test.txt -Algorithm MD5
$uploadedHash = Get-FileHash data\10mb_test.txt -Algorithm MD5
$downloadedHash = Get-FileHash downloads\10mb_downloaded.txt -Algorithm MD5

if ($originalHash.Hash -eq $uploadedHash.Hash -and $uploadedHash.Hash -eq $downloadedHash.Hash) {
    Write-Host "✓ 文件完整性验证通过" -ForegroundColor Green
} else {
    Write-Host "✗ 文件损坏" -ForegroundColor Red
}
```

### 测试场景 2：快速连接/断开

**创建测试脚本** `test_stress.ps1`：

```powershell
# 快速连接 10 次
1..10 | ForEach-Object {
    Write-Host "连接 $_"
    Start-Process powershell -ArgumentList "-Command", "cd bin; java data.SimpleFtpClient" -Wait
    Start-Sleep -Milliseconds 100
}
```

**预期结果**：
- 服务器正常处理所有连接
- 无资源泄漏
- 统计信息正确

### 测试场景 3：超时测试

**手动测试**：
1. 启动客户端
2. 登录成功
3. 等待 60 秒不执行任何命令
4. 尝试执行命令

**预期结果**：
- 服务器在 60 秒后断开连接
- 客户端收到 "421 连接超时"

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
...
[ClientSession] alice <- STOR test_delete.txt
[ClientSession] 文件 test_delete.txt 接收完成: 35 字节
[ClientSession] alice <- 226 传输完成
[ClientSession] alice <- DELE test_delete.txt
[ClientSession] 文件已删除: test_delete.txt
[ClientSession] alice <- 250 文件 test_delete.txt 已删除
...
[统计] 总连接数: 1, 活跃连接: 1, 传输字节: 12345, 总命令数: 15
```

### 客户端输出

```
========== FTP 客户端测试开始 ==========

[客户端] 连接到 127.0.0.1:2121
[服务器] 220 简易 FTP 服务器已准备好
...

[测试 2] 上传测试文件
[STOR 命令流程]
...
[服务器] 226 传输完成

[测试 3] 验证上传：列出根目录
===== 目录列表 =====
  public/
  upload/
  test.txt (38 字节)
  test_delete.txt (35 字节)  ← 刚上传的文件
共 4 项
====================

[测试 4] 删除刚上传的文件

[DELE 命令流程]
[客户端] 发送: DELE test_delete.txt
[服务器] 250 文件 test_delete.txt 已删除
[客户端] ✓ 文件删除成功

[测试 5] 验证删除：列出根目录
===== 目录列表 =====
  public/
  upload/
  test.txt (38 字节)
共 3 项
====================

[测试 6] 尝试删除不存在的文件

[DELE 命令流程]
[客户端] 发送: DELE nonexistent.txt
[服务器] 550 文件不存在: nonexistent.txt
[客户端] ✗ 文件删除失败

[测试 7] 尝试删除目录（应该失败）

[DELE 命令流程]
[客户端] 发送: DELE public
[服务器] 550 public 不是普通文件（不能删除目录）
[客户端] ✗ 文件删除失败
```

---

## 常见错误与排查

| 错误 | 原因 | 解决方法 |
|------|------|---------|
| `450 无法删除文件` | 文件被占用或权限不足 | 关闭占用文件的程序，检查权限 |
| `421 连接超时` | Socket 读取超时 | 正常，符合预期 |
| `502 不支持的命令` | 命令拼写错误或未实现 | 检查命令名，参考 HELP |
| 多客户端冲突 | 共享资源未加锁 | Java NIO Files 操作是线程安全的 |
| 统计信息不准确 | 未正确调用统计方法 | 检查 onClientConnect/Disconnect 调用 |

---

## 检查清单

完成 Day6，你应该能做到：

- [ ] `ClientSession.java` 增加了 `handleDele()` 方法
- [ ] switch 语句中添加了 DELE case
- [ ] HELP 命令显示 DELE 说明
- [ ] 增加了超时设置（setSoTimeout）
- [ ] 增加了空闲超时检查
- [ ] 完善了错误响应码（425/426/450/501/502/530/550/553）
- [ ] `DataConnection.java` 增加了连接和读写超时
- [ ] `SimpleFtpClient.java` 增加了 `dele()` 方法
- [ ] 创建了 `SessionStatistics.java`（可选）
- [ ] 服务器启动正常
- [ ] 可以删除文件
- [ ] 删除不存在的文件收到 550 错误
- [ ] 删除目录收到 550 错误
- [ ] 多客户端可以同时连接
- [ ] 并发上传/下载无冲突
- [ ] 超时机制正常工作
- [ ] 统计信息准确（如果实现）
- [ ] 大文件传输（10MB+）正常
- [ ] 快速连接/断开无资源泄漏

---

## 核心概念回顾

### DELE 命令流程

```
客户端                              服务器
  |                                   |
  |--- DELE test.txt ---------------->|
  |                   验证路径安全性   |
  |                   检查文件存在     |
  |                   Files.delete()   |
  |<----------------------------------|
  |    250 File deleted               |
```

### 超时机制总结

| 超时类型 | 设置方法 | 超时后行为 |
|---------|---------|----------|
| Socket 读取超时 | socket.setSoTimeout() | 抛出 SocketTimeoutException |
| 连接超时 | socket.connect(addr, timeout) | 抛出 SocketTimeoutException |
| 空闲超时 | 手动检查 lastActiveTime | 发送 421 并断开连接 |

### FTP 响应码速查表

| 场景 | 响应码 | 消息 |
|------|-------|------|
| 连接成功 | 220 | Service ready |
| 登录成功 | 230 | User logged in |
| 命令成功 | 200 | Command okay |
| 文件操作成功 | 250 | File action completed |
| 传输完成 | 226 | Transfer complete |
| 需要密码 | 331 | Password required |
| 未登录 | 530 | Not logged in |
| 未设置数据端口 | 425 | Can't open data connection |
| 数据连接失败 | 426 | Connection aborted |
| 临时文件错误 | 450 | File action not taken |
| 语法错误 | 500/501 | Syntax error |
| 未实现 | 502 | Command not implemented |
| 文件不存在 | 550 | File not available |
| 文件名非法 | 553 | Filename not allowed |

---

## 下一步预告（Day7）

- **代码清理**：删除调试日志、优化注释
- **完善 README.md**：项目说明、使用方法、架构图
- **准备演示**：录制 Demo 视频或准备截图
- **文档整理**：整理所有文档，生成目录
- **测试报告**：编写测试报告，记录测试结果
- **课设答辩准备**：准备 PPT、讲解要点

---

## 参考资源

- [FTP 响应码完整列表](https://tools.ietf.org/html/rfc959#section-4.2)
- [Java Socket 超时设置](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html#setSoTimeout-int-)
- [Java AtomicInteger](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicInteger.html)
- [Files.delete()](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html#delete-java.nio.file.Path-)

---

## 常见问题（FAQ）

**Q：为什么不允许删除目录？**  
A：FTP 标准中，DELE 只用于删除文件。删除目录需要 RMD 命令（本课设不要求）。

**Q：如何实现目录删除？**  
A：实现 RMD 命令，使用 `Files.delete()` 删除空目录，或递归删除非空目录。

**Q：setSoTimeout() 和空闲超时有什么区别？**  
A：
- **setSoTimeout()**：读取操作的最大等待时间（单次操作）
- **空闲超时**：两次命令之间的最大间隔时间（会话级别）

**Q：如何测试超时？**  
A：
- **Socket 超时**：暂停调试器，等待超过 60 秒
- **空闲超时**：登录后不执行命令，等待 5 分钟

**Q：多客户端操作同一文件会冲突吗？**  
A：
- **读操作**：无冲突，可并发
- **写操作**：Java NIO Files 操作是原子的，但并发写入可能导致数据混乱
- **删除操作**：第二个删除会收到 550 错误（文件不存在）

**Q：如何限制最大并发连接数？**  
A：在 FtpServer 中添加计数器，拒绝超过阈值的连接：
```java
if (SessionStatistics.getActiveConnections() >= MAX_CONNECTIONS) {
    Socket client = serverSocket.accept();
    client.close();  // 拒绝连接
}
```

**Q：统计信息为什么要用 AtomicInteger？**  
A：因为多线程环境下，普通 int 的自增操作不是原子的，可能导致统计不准确。

---

**恭喜你完成 Day6！你的 FTP 服务器已经具备完整功能和鲁棒性。** 🎉
