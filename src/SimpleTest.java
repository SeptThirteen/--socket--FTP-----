package data;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 简单的 FTP 连接测试
 * 用于快速验证服务器是否响应
 */
public class SimpleTest {
    public static void main(String[] args) throws Exception {
        System.out.println("[测试] 连接到 FTP 服务器...");
        
        Socket socket = new Socket("127.0.0.1", 2121);
        System.out.println("[测试] ✓ 已连接");
        
        // 读取欢迎消息
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String welcome = reader.readLine();
        System.out.println("[服务器] " + welcome);
        
        // 发送 USER 命令
        BufferedWriter writer = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write("USER alice\r\n");
        writer.flush();
        String resp1 = reader.readLine();
        System.out.println("[服务器] " + resp1);
        
        // 发送 PASS 命令
        writer.write("PASS 123456\r\n");
        writer.flush();
        String resp2 = reader.readLine();
        System.out.println("[服务器] " + resp2);
        
        // 发送 QUIT 命令
        writer.write("QUIT\r\n");
        writer.flush();
        String resp3 = reader.readLine();
        System.out.println("[服务器] " + resp3);
        
        socket.close();
        System.out.println("[测试] ✓ 连接测试完成");
    }
}
