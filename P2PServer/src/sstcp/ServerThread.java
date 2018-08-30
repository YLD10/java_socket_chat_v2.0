package sstcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Iterator;
import java.util.concurrent.atomic.LongAdder;

/**
 * ServerThread.java
 * 
 * @Author : YLD
 * @CreateTime : 2017/12/1 23:30
 * @LastModifTime : 2018/8/27 18:08
 * @Mean: 专门负责为某一位用户处理消息并进行转发的一个服务线程。 即把这一位用户发送的消息转发给指定的收信人。
 * 
 */
public class ServerThread implements Runnable {
    public String loginName; // 登录昵称，为用户唯一标识符
    private Socket socket; // socket对象
    private BufferedReader br; // 数据输入流
    private PrintWriter pw; // 数据输出流
    private Long lastChatTime; // 最近的对话时间
    private boolean alive = true; // 是否在线标志位，默认在线
    private boolean canRemove; // socket是否可以移除标志位，默认不可移除
    private static LongAdder count = new LongAdder(); // 统计连接数

    /**
     * 构造函数，初始化socket对象和最近对话时间
     * @param socket
     * @throws IOException
     */
    public ServerThread(Socket socket) throws IOException {
        this.socket = socket;
        // 设置socket发包缓冲为32k
        // socket.setSendBufferSize(32 * 1024);
        // 设置socket底层接收缓冲为32k
        // socket.setReceiveBufferSize(32 * 1024);
        // 关闭Nagle算法, 立即发包
        // socket.setTcpNoDelay(true);
        pw = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8")), true);
        br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        lastChatTime = System.currentTimeMillis();
    }

    /**
     *  线程的执行函数，线程构造完成后启动时默认执行此函数
     */
    @Override
    public void run() {
        // System.out.println("\n新连接，客户端的IP：" + socket.getInetAddress().getHostAddress()
        // + " ，端口：" + socket.getPort());

        StringBuilder sBuilder = new StringBuilder(); // 消息载体
        boolean flag = false; // 判断是否为对话数据类型的消息，默认不是对话，
                              // 而是建立/断开连接或者列表更新的消息
        boolean exit = true; // 退出标识

        while (true) { // 循环等待消息
            sBuilder.delete(0, sBuilder.length());
            flag = false;
            exit = true;

            try {
                // 将得到的socket输入流转换为封装后的缓冲输入流并赋值给br
                String str = null; // 暂存一行消息的载体

                while ((str = br.readLine()) != null) { // 循环接收消息直至一则消息接收完毕
                    if (str.length() > 6) { // 此行消息长度大于6，则有可能是消息首部或者消息尾部
                        if (str.length() > 7) { // 且此行消息长度大于7
                            if (str.startsWith("!@#$%^&*")) {
                                flag = true; // 说明这一行是对话数据的消息首部，
                                             // 消息首部会被保留下来，
                                             // 在match函数里才会被丢弃
                            }
                        } else if (str.startsWith("@#end#@")) {
                            exit = false;
                            break; // 则说明这一行是这则消息的尾部，消息已经全部接收完毕，
                                   // 应该跳出接收消息的循环，但不跳出等待消息的循环
                                   // 消息尾部在此处被丢掉了
                        }
                    }
                    sBuilder.append(str); // 将接收到的一行消息拼接到已接收到的消息内容的尾部
                    if (flag) { // 如果是对话数据类型的一行消息
                        sBuilder.append("\n"); // 则还应该补上这一行末尾的换行符
                    }
                }

                if (!exit && flag) { // 如果是对话数据类型的消息，flag为true
                    match(sBuilder.toString()); // 为已接收到的对话消息匹配指定收信人并进行转发
                    // 如果已接收到的消息首部前8个字符是"}{|\":?><"，则说明此消息为请求更新列表的消息
                } else if (!exit && sBuilder.substring(0, 8).equals(")(*&^%$#")) {
                    // 把此建立连接的消息首部从第8个字符起到出现"<>?:\"|{}"为止之间的字符串拿出来作为用户登录昵称
                    loginName = sBuilder.substring(8, sBuilder.indexOf("<>?:\"|{}"));
                    Server.users.add(loginName);
                    Server.inline.put(loginName, this);
                    updateAllPerson(); // 有一位新用户登录了，所有用户的会话对象列表都要进行更新
                } else if (exit) {
                    // System.out.println("disconnect");
                    break; // 跳出等待消息的循环，即准备断开连接，关闭socket连接
                }
            } catch (Exception e) { // 不知道为啥，就算没有socket连接过来，
                                    // 服务端还是会时不时收到陌生的socket连接，
                                    // 然后过了5s就会抛出消息读取异常
                e.printStackTrace();
                System.out.println("出现未知错误，线程强制关闭！");
                break; // 此时也应该跳出等待消息的循环，关闭这个错误的连接
            }
        }

        // 将该客户端从客户端维护队列中移除，即没法再找到该用户
        Server.users.remove(loginName);
        Server.inline.remove(loginName);

        updateAllPerson(); // 有一位用户退出了，所有用户的会话对象列表都要进行更新
        try {
            socket.shutdownOutput(); // 关闭输出流, 准备关闭连接
            count.increment();
            br.close(); // 关闭输入流
            socket.close(); // 关闭socket，断开服务端这边的连接
            System.out.println("count: " + count.intValue());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 收信人匹配函数
     * @param message
     */
    private void match(String message) {
        // 计数器，收信人昵称与群发标志位的拼接字符串的结束下标
        // "<>?:\"|{}"在这里起的作用与建立连接信息中的作用有所不同
        // 在这里不仅定界收信人昵称，同时也定界群发标志位
        int end = message.indexOf("<>?:\"|{}");
        String recvName = message.substring(8, end - 1); // 收信人昵称,8起始是为跳过消息首部
        String isMulti = message.substring(end - 1, end); // 是否群发的标志位
        // System.out.println(isMulti.equals("1") ? "群发" : "单聊"); // “1”为群发，“0”为单聊

        // 发信人的昵称，默认为本服务线程的用户昵称
        String sendName = loginName;
        // 找到收信用户
        ServerThread sThread = Server.inline.get(recvName);
        if (null == sThread) {
            return;
        }
        if (isMulti.equals("0")) { // 单聊
            // 如果能找到收信用户且在线则该用户就是收信人
            if (sThread.getAlive()) {
                if (recvName.equals(sendName)) { // 如果收信人就是发信人自己
                    sendName = "自己"; // 那么发信人一栏应该显示“自己”
                }
                // 为真正的对话内容加上首部信息，并调用收信人的服务进程中的消息发送函数sendMsg
                // 只有收信人的服务进程才能将消息通过socket发送给收信人的客户端
                sThread.sendMsg("@#data#@" + sendName + "<>?:\"|{}" + message.substring(end + 8) + "\n@#end#@");
            }
        } else { // 群发
            Server.messages.add("@#data#@" + sendName + "<>?:\"|{}" + message.substring(end + 8) + "\n@#end#@");
        }
    }

    /**
     * 消息发送函数，通过socket发送消息给客户端
     * @param msg
     */
    public void sendMsg(String msg) {
        // 消息放进输出流并刷新输出
        pw.println(msg);
        // 设置最近对话时间
        lastChatTime = System.currentTimeMillis();
    }

    /**
     *  更新所有用户的会话对象列表
     */
    private void updateAllPerson() {
        // 消息载体，初始化为列表更新请求消息的消息首部"@#person#@"
        StringBuilder sBuilder = new StringBuilder("@#person#@");
        // 遍历所有服务进程
        Iterator<String> iterator = Server.users.iterator();
        while (iterator.hasNext()) {
            // 在线用户之间的昵称用"@##@"隔开并放到消息内容中
            sBuilder.append(iterator.next() + "@##@");
        }
        sBuilder.append("\n@#end#@");
        Server.messages.add(sBuilder.toString());
    }

    /**
     * hashCode
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((loginName == null) ? 0 : loginName.hashCode());
        return result;
    }

    /**
     * equals
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ServerThread other = (ServerThread) obj;
        if (loginName == null) {
            if (other.loginName != null)
                return false;
        } else if (!loginName.equals(other.loginName))
            return false;
        return true;
    }

    public Long getLastChatTime() {
        return lastChatTime;
    }

    public void setLastChatTime(Long lastChatTime) {
        this.lastChatTime = lastChatTime;
    }

    public boolean getAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean getCanRemove() {
        return canRemove;
    }

    public void setCanRemove(boolean canRemove) {
        this.canRemove = canRemove;
    }
}