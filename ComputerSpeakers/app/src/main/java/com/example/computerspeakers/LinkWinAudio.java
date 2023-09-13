package com.example.computerspeakers;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

public class LinkWinAudio {
    private final String SERVER_IP; //ip地址
    private final int PORT;  //端口号
    private final int CHUNK;    //单次传输byte大小

    private int CHANNEL;    //单次传输byte大小

    private final AudioTrack audioTrack;  //实例化播发音频流
    public AssetManager assetManager;  //获取语音"断开连接"保存的位置assets
    public PowerManager.WakeLock wakeLock;    //系统休眠锁
    public boolean is_suspend = false;    //暂停

    private final SharedPreferences sharedPreferences_this;   //偏好内容
    private final String messageStr;   //携带个性化内容发送到服务器
    private Socket socketLink;   //开放接口调用心跳检测是否断开连接
    private final ReentrantLock lock = new ReentrantLock();   //心跳测试连接状态和音频接收的线程锁
    private DataInputStream dataInputStreamLink;    //开放接口调用销毁连接
    public boolean errorBack = false;   //开放检测是否已经停止了线程
    private Thread thread_start_socket_link;

    private Context ActivityContextLink;



    LinkWinAudio(String server_ip, SharedPreferences sharedPreferences, PowerManager.WakeLock wakeLock_, AssetManager assetManager_, Context ActivityContext){
        //测试连接
        runTestConnect();
        //创建个性化信息，发送到主机
        HashMap<String,String> ages_message = new HashMap<>();
        String rate = sharedPreferences.getString("do_audio_size","8000");
        String channels = sharedPreferences.getString("do_channels","2");
        String chunk = sharedPreferences.getString("do_chunk","1024");
        String port = sharedPreferences.getString("do_port","5000");

        ages_message.put("name",sharedPreferences.getString("do_name","user"));
        ages_message.put("rate",rate);
        ages_message.put("chunk",chunk);
        ages_message.put("channels",channels);
        JSONObject message_json = new JSONObject(ages_message);
        messageStr = message_json.toString();

        sharedPreferences_this = sharedPreferences;

        //初始化定义参数 音频输出使用AudioFormat
        int channelConfig = (channels.equals("1")) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;

        int bufferSize = AudioTrack.getMinBufferSize(Integer.parseInt(rate),
                channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                Integer.parseInt(rate), channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize,
                AudioTrack.MODE_STREAM);

        //连接参数
        SERVER_IP = server_ip;
        CHUNK = Integer.parseInt(chunk);
        CHANNEL = Integer.parseInt(channels);
        PORT = Integer.parseInt(port);
        //mainActivity传递的参数
        wakeLock = wakeLock_;
        assetManager = assetManager_;
        ActivityContextLink = ActivityContext;
    }

    public void run() {
        Thread thread_start_socket = new Thread(() ->{
            try{
                Socket socket = new Socket(SERVER_IP, PORT);    //创建socket连接
                socketLink = socket;
                InputStream inputStream = socket.getInputStream();    //获取接收主机方法
                OutputStream outputStream = socket.getOutputStream();    //获取发送主机方法
                DataInputStream dataInputStream = new DataInputStream(inputStream);   //获取接收主机对象
                dataInputStreamLink = dataInputStream;

                //2秒每次检测服务器是否断开
                handler.postDelayed(runnableTestConnect, 2000);

                //发送消息给主机，让其发送对应音频流到客户端  尝试发送10次，间隔500ms
                boolean initServer = false;
                byte[] dataToSendMessage = getFixedLengthByteArray(messageStr, 1024);  //字符串转byte
                for (int i=0; i<10;i++){
                    outputStream.write(dataToSendMessage);
                    Thread.sleep(500);
                    if(inputStream.available()>0){   //如果主机应答了
                        byte [] bytes_init_data = new byte[1024];
                        int bytesRead = inputStream.read(bytes_init_data);
                        if(bytesRead != -1 && byteToStr(bytes_init_data).equals("ok")){
                            initServer = true;
                            break;
                        }
                    }
                }
                if (!initServer){    //初始化失败
                    stop_link(true);
                    return;
                }
                //准备开始接收音频
                Log.d(TAG, "run: 准备开始接收音频");
                byte[] buffer_audio_data = new byte[CHUNK * CHANNEL];
                audioTrack.play();   //AudioTrack开始工作
                startWakeLock();    //开始禁止系统休眠
                int bytesRead;    //接收到数据
                while (socket.isConnected() && !Thread.interrupted()) {   //受到socket是否断开和手动停止控制
                    if (is_suspend) continue;  //手动暂停
                    if(inputStream.available()>0){
                        lock.lock();
                        try {
                            bytesRead = dataInputStream.read(buffer_audio_data);
                            if (bytesRead == -1) break;
                            audioTrack.write(buffer_audio_data, 0, bytesRead);    //播放接收到的音频流`
                        }finally {
                            lock.unlock();
                        }
                    }
                }
                //接收结束，准备断开连接
                stop_link(false);
            }catch (IOException | InterruptedException e){
                e.printStackTrace();
                stop_link(true);
                Looper.prepare();
                Toast.makeText(ActivityContextLink,"连接失败，请检查网络",Toast.LENGTH_SHORT).show();  //未正常运行，报个错
                Looper.loop();
            }
        });
        thread_start_socket.start();
        thread_start_socket_link = thread_start_socket;
    }

    private void stop_link(boolean isError){
        //只能被thread_start_socket中调用
        if (audioTrack != null){
            audioTrack.stop();
            audioTrack.release();
        }
        if (!isError) playDisconnect();  //播放断开连接
        stopWakeLock();    //结束系统休眠
        if (socketLink != null){
            try {
                socketLink.close();
                dataInputStreamLink.close();
            } catch (IOException e) {
                e.printStackTrace();
                //socket断开失败
            }
        }
        handler.removeCallbacks(runnableTestConnect);
        errorBack = true;
    }

    private void startWakeLock(){
        if (wakeLock == null) return;
        //开始停止后台休眠
        if (!wakeLock.isHeld() && sharedPreferences_this.getBoolean("do_wakelock",false)){
            wakeLock.acquire();
        }
    }
    private void stopWakeLock(){
        if (wakeLock == null) return;
        //重新允许系统后台睡眠
        if(wakeLock.isHeld()){
            wakeLock.release();
        }
    }

    private static byte[] getFixedLengthByteArray(String inputString, int fixedLength) throws UnsupportedEncodingException {
        // 字符串转byte
        byte[] inputBytes = inputString.getBytes(StandardCharsets.UTF_8);
        byte[] fixedLengthByteArray = new byte[fixedLength];

        // 将inputBytes拷贝到fixedLengthByteArray中，不足部分用0填充
        System.arraycopy(inputBytes, 0, fixedLengthByteArray, 0, Math.min(inputBytes.length, fixedLength));
        if (inputBytes.length < fixedLength) {
            for (int i = inputBytes.length; i < fixedLength; i++) {
                fixedLengthByteArray[i] = 0;
            }
        }

        return fixedLengthByteArray;
    }

    private static String byteToStr(byte[] buffer) {
        // byte转字符串
        try {
            int length = 0;
            for (int i = 0; i < buffer.length; ++i) {
                if (buffer[i] == 0) {
                    length = i;
                    break;
                }
            }
            return new String(buffer, 0, length, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void playDisconnect(){
        // 播放停止语音
        if(sharedPreferences_this.getBoolean("do_disablesounds",false)) return;
        try {
            AssetFileDescriptor afd = assetManager.openFd("Disconnect.mp3");
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getLength());
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnCompletionListener(MediaPlayer::release);
        }catch (IOException e){
            e.printStackTrace();
        }

    }
    private Handler handler;
    private Runnable runnableTestConnect;
    private void runTestConnect(){
        handler = new Handler();
        runnableTestConnect = () -> {
            Thread thread = new Thread(()->{
                lock.lock();
                try {
                    socketLink.sendUrgentData(65);
                }catch (IOException e){
                    e.printStackTrace();
                    stop_run();
                }finally {
                    lock.unlock();
                }
            });
            thread.start();
            handler.postDelayed(runnableTestConnect,2000);
        };
    }


    public void set_is_suspend(boolean suspend){
        //暂停
        is_suspend = suspend;
    }

    public boolean get_is_suspend(){
        return is_suspend;
    }

    public void stop_run(){
        //停止
        if(thread_start_socket_link != null){
            thread_start_socket_link.interrupt();
        }
    }

    public boolean get_is_stop(){
        if (thread_start_socket_link != null){
            return !thread_start_socket_link.isAlive();
        }
        return false;
    }
}
