package data;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 数据连接工具类
 * 
 * 负责：
 * 1. 主动连接到客户端的数据端口
 * 2. 发送数据（目录列表、文件内容等）
 * 3. 接收数据（上传的文件）
 * 4. 自动关闭连接
 */
public class DataConnection {
    
    /** 数据连接的超时时间（毫秒）*/
    private static final int TIMEOUT = 30000;  // 30 秒
    
    /** 数据 Socket */
    private Socket dataSocket;
    
    /** 输入流 */
    private InputStream inputStream;
    
    /** 输出流 */
    private OutputStream outputStream;
    
    /**
     * 连接到客户端的数据端口（主动模式）
     * 
     * @param address 客户端数据端口地址
     * @throws IOException 如果连接失败
     */
    public void connect(InetSocketAddress address) throws IOException {
        System.out.println("[DataConnection] 正在连接到客户端数据端口: " + address);
        
        // 创建 Socket 并连接
        dataSocket = new Socket();
        dataSocket.setSoTimeout(TIMEOUT);
        dataSocket.connect(address, TIMEOUT);
        
        // 获取输入输出流
        inputStream = dataSocket.getInputStream();
        outputStream = dataSocket.getOutputStream();
        
        System.out.println("[DataConnection] 数据连接已建立");
    }
    
    /**
     * 发送文本数据（如目录列表）
     * 
     * @param text 要发送的文本内容
     * @throws IOException 如果发送失败
     */
    public void sendText(String text) throws IOException {
        if (outputStream == null) {
            throw new IOException("Data connection not established");
        }
        
        // 将文本转换为字节并发送
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        outputStream.write(data);
        outputStream.flush();
        
        System.out.println("[DataConnection] 已发送 " + data.length + " 字节文本数据");
    }
    
    /**
     * 发送二进制数据（如文件内容）
     * 
     * @param data 要发送的字节数组
     * @throws IOException 如果发送失败
     */
    public void sendBytes(byte[] data) throws IOException {
        if (outputStream == null) {
            throw new IOException("Data connection not established");
        }
        
        outputStream.write(data);
        outputStream.flush();
        
        System.out.println("[DataConnection] 已发送 " + data.length + " 字节二进制数据");
    }
    
    /**
     * 从输入流读取数据（用于上传文件）
     * 
     * @param buffer 缓冲区
     * @return 读取的字节数，-1 表示流结束
     * @throws IOException 如果读取失败
     */
    public int read(byte[] buffer) throws IOException {
        if (inputStream == null) {
            throw new IOException("Data connection not established");
        }
        return inputStream.read(buffer);
    }
    
    /**
     * 关闭数据连接
     * 
     * 数据传输完成后必须关闭，释放资源
     */
    public void close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
        
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
        
        try {
            if (dataSocket != null && !dataSocket.isClosed()) {
                dataSocket.close();
                System.out.println("[DataConnection] 数据连接已关闭");
            }
        } catch (IOException e) {
            // 忽略关闭错误
        }
    }
    
    /**
     * 检查连接是否已建立
     */
    public boolean isConnected() {
        return dataSocket != null && dataSocket.isConnected() && !dataSocket.isClosed();
    }
}
