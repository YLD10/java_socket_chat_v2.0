package sstcp;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server.java
 * 
 * @Author : YLD
 * @CreateTime : 2017/12/1 23:30
 * @LastModifTime : 2018/8/27 18:08
 * @Mean : 服务端程序。接受客户端的连接请求，并维护所有的客户端连接。
 *
 */
public class Server {
    // 用户昵称维护队列
    public static ConcurrentLinkedQueue<String> users = new ConcurrentLinkedQueue<String>();
    // 群发消息发送队列
    public static ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<String>();
    // 客户端在线连接维护队列
    public static ConcurrentHashMap<String, ServerThread> inline = new ConcurrentHashMap<String, ServerThread>(1024);
    // 客户端连接池
    private ExecutorService threadPool = Executors.newFixedThreadPool(1025);

    public Server() {
        Run(); // 主要执行函数
    }

    /**
     *  主要执行函数
     */
    public void Run() {
        ServerSocket serverSocket = null;
        try {
            // 初始化服务端socket连接，监听端口32768
            serverSocket = new ServerSocket(32768);
            System.out.println("服务端启动成功！");

            // 创建用于群发消息的辅助线程
            threadPool.execute(new MultiSendTread());
            while (true) { // 一直循环等待客户端连接的到来
                try {
                    // 创建针对这一客户端连接的维护线程
                    threadPool.execute(new ServerThread(serverSocket.accept()));
                    // System.out.println("服务端监听到客户端请求！");
                } catch (IOException e) {
                    e.printStackTrace();
                    System.out.println("服务端连接失败！");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("服务端启动失败！");
        } finally {
            try {
                // 如果服务端socket连接对象不为空
                if (serverSocket != null) {
                    serverSocket.close(); // 关闭服务端连接对象
                    System.out.println("成功关闭服务器！");
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.out.println("关闭服务器失败！");
            }finally {
                threadPool.shutdown();
            }
        }
    }

    /**
     * 内部线程类, 负责群发消息
     * @author YLD10
     * 
     */
    private class MultiSendTread implements Runnable {
        @Override
        public void run() {
            while (true) {
                if (messages.size() > 0) {
                    String msg = messages.remove();
                    String sendName = "";
                    
                    if (msg.startsWith("@#data#@")) {
                        sendName = msg.substring(8, msg.indexOf("<>?:\"|{}")); // 收信人昵称,8起始是为跳过消息首部
                    }
                    Collection<ServerThread> sThreads = inline.values();
                    for (Iterator<ServerThread> iterator = sThreads.iterator(); iterator.hasNext();) {
                        ServerThread serverThread = iterator.next();
                        // 群发消息不发给自己
                        if (sendName.equals(serverThread.loginName)) {
                            continue;
                        }
                        serverThread.sendMsg(msg);
                    }
                }else {
                    // 如果当前没有消息, 就先睡眠一阵
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}