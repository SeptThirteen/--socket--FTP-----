package data;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 客户端会话处理器
 * 
 * 每当一个客户端连接到 FTP 服务器时，就创建一个 ClientSession 实例
 * 这个实例在一个单独的线程中运行，负责：
 * 1. 与该客户端通过 Socket 进行通信
 * 2. 读取客户端的 FTP 命令
 * 3. 维护登录状态和其他会话数据
 * 4. 发送响应码和消息给客户端
 */
public class ClientSession implements Runnable {

    /**
     * 会话读写使用的字符集。为兼容 Windows 自带 telnet（默认 GBK），可切换为 GBK。
     * 如需改回 UTF-8，只需调整此处常量。
     */
    private static final Charset CONN_CHARSET = Charset.forName("UTF-8");
    
    // ==================== 成员变量 ====================
    
    /** 与客户端通信的 Socket */
    private final Socket controlSocket;
    
    /** 从 Socket 读取数据的 Reader（一行一行地读） */
    private final BufferedReader in;
    
    /** 向 Socket 写入数据的 Writer（一行一行地写） */
    private final BufferedWriter out;
    
    /** 用户表管理器 */
    private final UserStore userStore;
    
    /** 路径验证器 */
    private final PathValidator pathValidator;

    // ==================== 会话状态 ====================
    
    /** 是否已认证（已登录）*/
    private boolean authenticated = false;
    
    /** 当前登录的用户名（未登录时为 null）*/
    private String currentUser = null;
    
    /** 当前工作目录（相对于根目录的路径，如 "/" 或 "/upload"） */
    private String currentWorkingDir = "/";
    
    /** 客户端数据端口地址（由 PORT 命令设置）*/
    private InetSocketAddress dataAddress = null;


    // ==================== 构造方法 ====================
    
    /**
     * 初始化一个会话
     * 
     * @param controlSocket 与客户端连接的 Socket
     * @param rootPath FTP 虚拟根目录路径
     * @param userStore 用户表管理器
     * @throws IOException 如果 Socket 读写出错
     */
    public ClientSession(Socket controlSocket, String rootPath, UserStore userStore) throws IOException {
        this.controlSocket = controlSocket;
        this.userStore = userStore;
        // 初始化路径验证器
        this.pathValidator = new PathValidator(rootPath);
        
        // 初始化当前工作目录为根目录
        this.currentWorkingDir = "/";
            


        // 从 Socket 获取输入流和输出流
        // InputStreamReader 将字节流转换为字符流，因为 FTP 命令是文本协议，需要把字节流转换成字符流并用带缓冲的按行读写工具来正确、可靠且高效地处理命令和回复。
        // BufferedReader 提供按行读取的便利
        InputStream socketInput = controlSocket.getInputStream();
        this.in = new BufferedReader(
            new InputStreamReader(socketInput, CONN_CHARSET)
        );
        
        // 同理，BufferedWriter 提供按行写的便利
        OutputStream socketOutput = controlSocket.getOutputStream();
        this.out = new BufferedWriter(
            new OutputStreamWriter(socketOutput, CONN_CHARSET)
        );
    }
    
    // ==================== 核心方法 ====================
    
    /**
     * 线程运行方法
     * 当这个会话对象被提交给线程执行时，此方法会被调用
     * 
     * 整个生命周期：
     * 1. 发送欢迎码 220
     * 2. 循环读取客户端命令并处理
     * 3. 处理 QUIT 时退出循环，关闭连接
     */
    @Override
    public void run() {
        try {
            // 设置 Socket 超时时间（毫秒）：300 秒
            // 如果 300 秒内没有收到数据，Socket 会抛出异常
            controlSocket.setSoTimeout(300000);
            
            // 发送欢迎码
            reply(220, "简易 FTP 服务器已准备好");
            
            // 命令处理主循环
            String line;
            while ((line = in.readLine()) != null) {
                // 去掉首尾空白
                line = line.trim();
                
                // 忽略空行
                if (line.isEmpty()) {
                    continue;
                }
                
                // 处理这条命令
                handleCommand(line);
                
                // 如果用户已经下达了 QUIT，则在下一个循环时 in.readLine() 会返回 null
            }
        } catch (IOException e) {
            // 客户端连接关闭或网络错误
            System.out.println("[ClientSession] 客户端" + currentUser + "断开连接: " + e.getMessage());
        } finally {
            // 确保连接被正确关闭
            try {
                controlSocket.close();
            } catch (IOException e) {
                // 忽略关闭时的错误
            }
        }
    }
    
    /**
     * 处理一条 FTP 命令
     * 
     * @param commandLine 整条命令（如 "USER alice"）
     */
    private void handleCommand(String commandLine) {
        try {
            // 按空格拆分：第一个词是命令，剩下的是参数
            // split("\\s+", 2) 表示：按任意个空白分割，最多分 2 段
            // 例如 "USER alice" → ["USER", "alice"]
            // 例如 "STOR  file.txt" → ["STOR", "file.txt"]
            String[] parts = commandLine.split("\\s+", 2);
            String cmd = parts[0].toUpperCase(Locale.ROOT);  // 命令转大写，parts[0] 是命令
            String arg = (parts.length > 1) ? parts[1] : "";  // 获取参数，若无则空字符串
            
            // 根据命令类型进行分发处理，核心命令处理函数，这个实现逻辑然后调用具体的处理函数
            switch (cmd) {
                case "USER":
                    handleUser(arg);
                    break;
                case "PASS":
                    handlePass(arg);
                    break;
                case "QUIT":
                    handleQuit();
                    break;
                case "EXIT":
                    handleQuit();
                    break;
                case "HELP":
                    handleHelp();
                    break;
                case "CWD":
                    handleCwd(arg);
                    break;
                case "PWD":
                    handlePwd();
                    break;
                case "PORT":
                    if (!authenticated) {
                        reply(530, "请先登录");
                    } else {
                        handlePort(arg);
                    }
                    break;
                case "LIST":
                    if (!authenticated) {
                        reply(530, "请先登录");
                    } else {
                        handleList();
                    }
                    break;
                default:
                    // 未知命令
                    reply(500, "Unknown command: " + cmd);
            }
        } catch (Exception e) {
            // 命令处理中出错，发送错误响应
            try {
                reply(500, "服务器错误: " + e.getMessage());
            } catch (IOException ignored) {
                // 如果发送错误信息也失败了，放弃
            }
        }
    }
    
    // ==================== 命令处理函数 ====================
    
    /**
     * 处理 USER 命令
     * 客户端要求：USER <username>
     * 服务器响应：
     *   - 用户存在 → 331（需要密码）
     *   - 用户不存在 → 530（登录失败）
     */
    private void handleUser(String username) throws IOException {
        // 1. 参数校验
        //sername.trim().isEmpty()把用户名前后空格去掉后，检查是否为空字符串
        if (username == null || username.trim().isEmpty()) {
            reply(501, "USER 命令需要一个参数");
            return;
        }
        //去掉前后空格的用户名
        username = username.trim();
        
        // 2. 在存储中检查用户是否存在
        if (!userStore.userExists(username)) {
            reply(530, "无效的用户");
            return;
        }
        
        // 3. 用户存在，记录下来，等待 PASS 命令
        currentUser = username;
        authenticated = false;  // 还未通过密码认证
        reply(331, "用户名正确，需要密码");
    }
    

    /**
     * 处理 PASS 命令
     * 客户端要求：PASS <password>
     * 服务器响应：
     *   - 认证成功 → 230（已登录）
     *   - 认证失败或未先发送 USER → 530（登录失败）
     */
    private void handlePass(String password) throws IOException {
        // 1. 参数校验
        if (password == null || password.trim().isEmpty()) {
            reply(501, "PASS 命令需要一个参数");
            return;
        }
        
        password = password.trim();
        
        // 2. 检查是否已经接收到 USER 命令
        if (currentUser == null) {
            reply(503, "请先使用 USER 命令登录");
            return;
        }
        
        // 3. 校验密码
        if (userStore.authenticate(currentUser, password)) {
            // 密码正确，认证成功
            authenticated = true;
            reply(230, "用户 " + currentUser + " 已登录");
        } else {
            // 密码错误
            currentUser = null;
            authenticated = false;
            reply(530, "登录失败");
        }
    }
    
    /**
     * 处理 QUIT 命令
     * 客户端要求：QUIT
     * 服务器响应：221（再见），然后关闭连接
     */
    private void handleQuit() throws IOException {
        reply(221, "再见");
        // 关闭连接，主循环中 readLine() 会返回 null，自动退出
        controlSocket.close();
    }

    /**
     * 处理 CWD 命令
     * 客户端要求：CWD <目录>
     * 服务器响应：
     *   - 成功 → 250（目录已更改）
     *   - 失败 → 550（目录不可用）
     */
    private void handleCwd(String dir) throws IOException {
        if (!authenticated) {
            reply(530, "请先登录");
            return;
        }
        
        if (dir == null || dir.trim().isEmpty()) {
            reply(501, "CWD 命令需要一个参数");
            return;
        }
        
        dir = dir.trim();
        
        try {
            //* 获取当前目录的绝对路径 */
            Path newPath = pathValidator.resolvePath(currentWorkingDir, dir);
            //* 验证该路径是否为有效目录 */
            if (pathValidator.isValidDirectory(newPath)) {
                // 更新当前工作目录（相对于根目录的路径）
                currentWorkingDir = pathValidator.toVirtualPath(newPath);
                reply(250, "目录已更改到 " + currentWorkingDir);
            } else {
                reply(550, "目录不可用");
            }
        } catch (SecurityException e) {
            reply(550, "访问被拒绝");
        } catch (IOException e) {
            reply(550, "目录不可用");
        }
    }
    
    /**
     * 处理 PWD 命令
     * 客户端要求：PWD
     * 服务器响应：257 "<当前目录>"
     */
    private void handlePwd() throws IOException {
        if (!authenticated) {
            reply(530, "请先登录");
            return;
        }
        
        reply(257, "\"" + currentWorkingDir + "\" 是当前目录");
    }    



    /**
     * 处理 HELP 命令（可选）
     * 显示支持的命令列表
     */
    private void handleHelp() throws IOException {
        reply(214, "支持的命令:");
        out.write("  user <用户名>  - 使用用户名登录\r\n");
        out.write("  pass <密码>  - 提供密码\r\n");
        out.write("  port h1,h2,h3,h4,p1,p2 - 设置数据端口\r\n");
        out.write("  list - 列出目录内容\r\n");
        out.write("  quit - 断开连接\r\n");
        out.write("  cwd <目录>  - 更改当前目录\r\n");
        out.write("  pwd - 显示当前目录\r\n");
        out.write("  help - 显示此消息\r\n");
        out.flush();
    }
    
    /**
     * 处理 PORT 命令 - 设置客户端数据端口
     * 
     * 命令格式：PORT h1,h2,h3,h4,p1,p2
     * 
     * @param arg 格式为 "h1,h2,h3,h4,p1,p2" 的字符串
     */
    private void handlePort(String arg) throws IOException {
        // 1. 参数校验
        if (arg == null || arg.trim().isEmpty()) {
            reply(501, "PORT 命令需要参数");
            return;
        }
        
        arg = arg.trim();
        
        try {
            // 2. 解析参数
            String[] parts = arg.split(",");
            
            if (parts.length != 6) {
                reply(501, "PORT 命令格式: h1,h2,h3,h4,p1,p2");
                return;
            }
            
            // 3. 组装 IP 地址
            String ip = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            
            // 4. 计算端口：port = p1 * 256 + p2
            int p1 = Integer.parseInt(parts[4]);
            int p2 = Integer.parseInt(parts[5]);
            int port = p1 * 256 + p2;
            
            // 5. 端口范围校验
            if (port < 1024 || port > 65535) {
                reply(501, "无效的端口号: " + port);
                return;
            }
            
            // 6. 保存数据端口地址
            dataAddress = new InetSocketAddress(ip, port);
            
            System.out.println("[ClientSession] 客户端数据端口设置为: " + dataAddress);
            reply(200, "PORT 命令执行成功");
            
        } catch (NumberFormatException e) {
            reply(501, "PORT 命令参数无效: " + e.getMessage());
        } catch (Exception e) {
            reply(501, "PORT 命令执行失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理 LIST 命令 - 列出当前目录内容
     * 
     * 工作流程：
     * 1. 检查是否已设置数据端口（PORT 命令）
     * 2. 发送 150 响应（即将打开数据连接）
     * 3. 建立数据连接
     * 4. 读取当前目录的文件列表
     * 5. 通过数据连接发送列表
     * 6. 关闭数据连接
     * 7. 发送 226 响应（传输完成）
     */
    private void handleList() throws IOException {
        // 1. 检查是否已设置数据端口
        if (dataAddress == null) {
            reply(425, "请先使用 PORT 命令");
            return;
        }
        
        // 2. 解析当前工作目录对应的实际路径
        Path currentDir;
        try {
            currentDir = pathValidator.resolvePath(currentWorkingDir, ".");
        } catch (Exception e) {
            reply(550, "无法访问目录: " + e.getMessage());
            return;
        }
        
        // 3. 检查目录是否存在
        if (!Files.exists(currentDir) || !Files.isDirectory(currentDir)) {
            reply(550, "目录不存在");
            return;
        }
        
        // 4. 发送"即将打开数据连接"的响应
        reply(150, "正在打开 ASCII 模式数据连接以获取文件列表");
        
        // 5. 建立数据连接并传输目录列表
        DataConnection dataConn = new DataConnection();
        try {
            // 连接到客户端数据端口
            dataConn.connect(dataAddress);
            
            // 构建目录列表文本
            StringBuilder listBuilder = new StringBuilder();
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
                for (Path entry : stream) {
                    // 获取文件名
                    String filename = entry.getFileName().toString();
                    
                    // 判断是否为目录
                    if (Files.isDirectory(entry)) {
                        // 目录名后加 / 标识
                        listBuilder.append(filename).append("/\r\n");
                    } else {
                        // 普通文件，也可以显示大小
                        long size = Files.size(entry);
                        listBuilder.append(filename).append(" (").append(size).append(" 字节)\r\n");
                    }
                }
            }
            
            // 如果目录为空
            if (listBuilder.length() == 0) {
                listBuilder.append("(空目录)\r\n");
            }
            
            // 发送列表数据
            String listText = listBuilder.toString();
            dataConn.sendText(listText);
            
            System.out.println("[ClientSession] 已发送目录列表，共 " + listText.length() + " 字节");
            
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
        
        // 6. 发送传输完成响应
        reply(226, "传输完成");
    }

    
    // ==================== 工具方法 ====================
    /**
     * 向客户端发送 FTP 响应
     * FTP 响应格式：<code> <message>\r\n
     * 例如：220 Simple FTP Server Ready\r\n
     * 
     * @param code FTP 响应码（3 位数）
     * @param message 响应消息
     */
    private void reply(int code, String message) throws IOException {
        // 格式化响应：码 + 空格 + 消息 + 换行符
        String response = code + " " + message + "\r\n";
        out.write(response);
        out.flush();// 刷新缓冲区
        
        // 可选：打印到服务器日志，便于调试
        System.out.println("[ClientSession] " + currentUser + " <- " + response.trim());
    }
}