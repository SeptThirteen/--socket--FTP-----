package data;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * 改进的简单 FTP 客户端（用于测试 Day3）
 * 
 * 修复了原版的问题：
 * - 确保 ServerSocket 正确初始化和关闭
 * - 明确的错误处理和日志
 * - 避免异常导致的流程中断
 */
public class SimpleFtpClient {
    private Socket controlSocket;
    private BufferedReader in;
    private BufferedWriter out;
    
    public static void main(String[] args) {
        SimpleFtpClient client = new SimpleFtpClient();
        try {
            System.out.println("========== FTP 客户端测试开始 ==========\n");
            
            client.connect("127.0.0.1", 2121);
            client.login("alice", "123456");
            client.pwd();
            
            System.out.println("\n[测试 1] 列出根目录");
            client.list();
            
            System.out.println("\n[测试 2] 切换到 public 目录");
            client.cwd("public");
            client.pwd();
            client.list();
            
            System.out.println("\n[测试 3] 切换回上级目录");
            client.cwd("..");
            client.pwd();
            client.list();
            
            System.out.println("\n[测试 4] 切换到 upload 目录");
            client.cwd("upload");
            client.pwd();
            client.list();
            
            System.out.println("\n[测试完成] 断开连接");
            client.quit();
            
        } catch (Exception e) {
            System.err.println("[致命错误] " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 连接到 FTP 服务器
     */
    public void connect(String host, int port) throws IOException {
        System.out.println("[客户端] 连接到 " + host + ":" + port);
        controlSocket = new Socket(host, port);
        in = new BufferedReader(new InputStreamReader(
            controlSocket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(
            controlSocket.getOutputStream(), StandardCharsets.UTF_8));
        
        // 读取欢迎消息
        String welcome = in.readLine();
        System.out.println("[服务器] " + welcome);
    }
    
    /**
     * 登录
     */
    public void login(String user, String pass) throws IOException {
        System.out.println("\n[登录流程]");
        sendCommand("USER " + user);
        sendCommand("PASS " + pass);
    }
    
    /**
     * 显示当前目录
     */
    public void pwd() throws IOException {
        sendCommand("PWD");
    }
    
    /**
     * 切换目录
     */
    public void cwd(String path) throws IOException {
        sendCommand("CWD " + path);
    }
    
    /**
     * 列出目录 - 核心方法，需要数据连接
     */
    public void list() throws IOException {
        ServerSocket tempServerSocket = null;
        Socket dataSocket = null;
        
        try {
            // 1. 创建 ServerSocket 监听本地端口
            tempServerSocket = new ServerSocket(0);  // 0 = 系统分配随机端口
            int dataPort = tempServerSocket.getLocalPort();
            
            System.out.println("\n[LIST 命令流程]");
            System.out.println("[客户端] 本地数据端口: " + dataPort);
            
            // 2. 计算 PORT 命令的参数
            int p1 = dataPort / 256;
            int p2 = dataPort % 256;
            
            System.out.println("[客户端] 计算 PORT 参数: p1=" + p1 + ", p2=" + p2);
            System.out.println("[客户端] 实际端口验证: " + (p1 * 256 + p2));
            
            // 3. 发送 PORT 命令
            String portCmd = "PORT 127,0,0,1," + p1 + "," + p2;
            System.out.println("[客户端] 发送: " + portCmd);
            
            out.write(portCmd + "\r\n");
            out.flush();
            
            // 4. 读取 PORT 响应
            String portResponse = in.readLine();
            System.out.println("[服务器] " + portResponse);
            
            if (!portResponse.startsWith("200")) {
                System.err.println("[错误] PORT 命令失败，服务器返回: " + portResponse);
                return;
            }
            
            // 5. 发送 LIST 命令
            System.out.println("[客户端] 发送: LIST");
            out.write("LIST\r\n");
            out.flush();
            
            // 6. 读取 150 响应（即将打开数据连接）
            String response150 = in.readLine();
            System.out.println("[服务器] " + response150);
            
            if (!response150.startsWith("150")) {
                System.err.println("[错误] LIST 命令响应异常: " + response150);
                // 继续尝试读取 226 或错误信息
                String errorResponse = in.readLine();
                System.out.println("[服务器] " + errorResponse);
                return;
            }
            
            // 7. *** 关键步骤 *** 等待服务器的数据连接
            System.out.println("[客户端] 等待服务器的数据连接...");
            
            // 设置 accept 超时，防止无限等待
            tempServerSocket.setSoTimeout(10000);  // 10 秒超时
            
            dataSocket = tempServerSocket.accept();
            System.out.println("[客户端] ✓ 数据连接已建立");
            
            // 8. 读取目录列表数据
            BufferedReader dataIn = new BufferedReader(new InputStreamReader(
                dataSocket.getInputStream(), StandardCharsets.UTF_8));
            
            System.out.println("===== 目录列表 =====");
            String line;
            int lineCount = 0;
            while ((line = dataIn.readLine()) != null) {
                System.out.println("  " + line);
                lineCount++;
            }
            System.out.println("共 " + lineCount + " 项");
            System.out.println("====================");
            
            dataIn.close();
            dataSocket.close();
            
            // 9. 读取 226 响应（传输完成）
            String response226 = in.readLine();
            System.out.println("[服务器] " + response226);
            
        } catch (SocketTimeoutException e) {
            System.err.println("[错误] 等待数据连接超时 (10秒): " + e.getMessage());
        } catch (ConnectException e) {
            System.err.println("[错误] 服务器无法连接到客户端: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("[错误] I/O 异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // 确保资源正确释放
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
    
    /**
     * 断开连接
     */
    public void quit() throws IOException {
        sendCommand("QUIT");
        controlSocket.close();
    }
    
    /**
     * 发送命令并读取单行响应
     */
    private void sendCommand(String cmd) throws IOException {
        System.out.println("[客户端] 发送: " + cmd);
        out.write(cmd + "\r\n");
        out.flush();
        
        String response = in.readLine();
        System.out.println("[服务器] " + response);
    }
}
