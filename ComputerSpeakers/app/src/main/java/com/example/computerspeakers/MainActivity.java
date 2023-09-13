package com.example.computerspeakers;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import android.content.pm.PackageManager;
import android.media.AudioManager;

import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.concurrent.locks.ReentrantLock;


public class MainActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener,AudioManager.OnAudioFocusChangeListener{

    private static final int PERMISSION_REQUEST_CODE = 1;
    LinkWinAudio linkWinAudio;   //播放主机内容
    public static SharedPreferences sharedPreferences;  //偏好设置
    private Handler handler;  //自动重新连接（定期执行）
    private Runnable ReconnectReconnect;  //自动重新连接（定期执行）
    private AudioManager audioManager;   //占用音频焦点，完成其他播放器播放暂停

    private final ReentrantLock lock_start = new ReentrantLock();   //测试和音频接收的线程锁

    private boolean connectOnStart;
    private boolean autoReConnect;
    private boolean isCloseAutoReConnect = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            // 权限未被授予，需要请求权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }

        //初始化播放焦点
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        //绑定开始连接按钮
        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(view -> downStart());

        //绑定结束连接按钮
        Button endButton = findViewById(R.id.button2);
        endButton.setOnClickListener(view -> downEnd());

        //设置跳转到个人偏好设置
        Button setUpButton = findViewById(R.id.button3);
        setUpButton.setOnClickListener(view -> {
            Intent intent = new Intent(MainActivity.this,PreferencesActivity.class);
            startActivity(intent);
        });

        //获取偏好设置内容，并把ip地址加入偏好内容
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        //获取保存在偏好内容里的ip地址
        String do_ip = sharedPreferences.getString("do_ip", "");
        if (!do_ip.equals("")){
            EditText editText = findViewById(R.id.editTextText3);
            editText.setText(do_ip);
        }

        //打开软件自动连接
        connectOnStart = sharedPreferences.getBoolean("do_connectonstart",true);
        autoReConnect = sharedPreferences.getBoolean("do_autoreconnect",false);
        if (connectOnStart && !do_ip.equals("")){
            endAudioStreamReceiver();
            startAudioStreamReceiver(false);
        }
        PeriodicReconnectRun();
        handler.postDelayed(ReconnectReconnect, 2000);
    }

    public void PeriodicReconnectRun(){
        handler = new Handler();
        ReconnectReconnect = () -> {
            if(connectOnStart && autoReConnect && !isCloseAutoReConnect){
                if(linkWinAudio == null){    //判断是否有连接
                    startAudioStreamReceiver(true);
                }
            }
            if(linkWinAudio != null && linkWinAudio.errorBack){
                endAudioStreamReceiver();
            }
            handler.postDelayed(ReconnectReconnect, 2000);
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Toast.makeText(MainActivity.this,"未授予文件读写",Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::recreate,5000);
            }
        }
    }

    private long PreferenceChangedTime = System.currentTimeMillis();
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        //电池优化是否开启
        if(sharedPreferences.getBoolean("do_wakelock",false)){
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        //更改了偏好内容，自动重新连接
        if(sharedPreferences.getBoolean("do_autoreconnectsettingschange",false)){
            if(System.currentTimeMillis() - PreferenceChangedTime > 1000){
                endAudioStreamReceiver();
                handler.postDelayed(()-> startAudioStreamReceiver(false),1000);
            }
        }
        //打开软件自动连接
        connectOnStart = sharedPreferences.getBoolean("do_connectonstart",true);
        autoReConnect = sharedPreferences.getBoolean("do_autoreconnect",false);
        PreferenceChangedTime = System.currentTimeMillis();



    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //销毁时，取消注册
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacks(ReconnectReconnect);
    }

    @Override
    protected void onPause() {
        super.onPause();
        //不在当前页面时，取消加入不熄灭屏幕变量
        if (sharedPreferences.getBoolean("do_sdwakelock",false) && sharedPreferences.getBoolean("do_wakelock", true)){
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        //在当前页面时，是否加入不熄灭屏幕变量
        if (sharedPreferences.getBoolean("do_sdwakelock",false) && sharedPreferences.getBoolean("do_wakelock", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    public void onAudioFocusChange(int i) {
        //焦点变更时，判断是否暂停播放
        if(!sharedPreferences.getBoolean("do_giveOtherPlay",true)){
            return;
        }
        Log.d(TAG, "onAudioFocusChange: " + i);
        switch (i){
            case AudioManager.AUDIOFOCUS_LOSS:   //失去焦点很长时间
                downEnd();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:    //暂时失去焦点
                if (linkWinAudio != null && !linkWinAudio.get_is_stop()){
                    linkWinAudio.set_is_suspend(true);
                }
                break;

            case AudioManager.AUDIOFOCUS_GAIN:    //你已获得焦点
                if (linkWinAudio != null && !linkWinAudio.get_is_stop() && linkWinAudio.get_is_suspend()){
                    linkWinAudio.set_is_suspend(false);
                }
                break;
        }
    }

    private void downStart(){
        if(isCloseAutoReConnect){
            isCloseAutoReConnect = false;
        }
        startAudioStreamReceiver(true);
    }
    private void downEnd(){
        if(!isCloseAutoReConnect){
            isCloseAutoReConnect = true;
        }
        endAudioStreamReceiver();
    }

    //开始监听主机音频流
    private void startAudioStreamReceiver(boolean isStartAudio){
        if(lock_start.isLocked() && isStartAudio) return;
        lock_start.lock();
        try {
            //ip地址判断是否正确
            EditText get_server_ip = findViewById(R.id.editTextText3);
            String get_str_server_ip = String.valueOf(get_server_ip.getText());
            if(!sharedPreferences.getBoolean("do_serverfulltext",false) && !isCorrectIp(get_str_server_ip)){
                Toast.makeText(MainActivity.this,"ip地址有误",Toast.LENGTH_SHORT).show();
                return;
            }
            //ip地址设置正确，添加进偏好内容
            sharedPreferences.edit().putString("do_ip", get_str_server_ip).apply();

            if(linkWinAudio == null){
                PowerManager.WakeLock wakeLock = null;
                if(sharedPreferences.getBoolean("do_wakelock",true)){
                    //防止系统休眠
                    PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:WakeLock");
                }

                //初始化获取主机音频流和播放的对象
                linkWinAudio = new LinkWinAudio(get_str_server_ip, sharedPreferences, wakeLock, getAssets(), getApplicationContext());
                //暂停播放；如果音频焦点未占用，则开始运行
                int result = audioManager.requestAudioFocus(this,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED){
                    linkWinAudio.run();
                }
                ImageView imageView = (ImageView) findViewById(R.id.imageView2);
                imageView.setImageResource(R.drawable.link_one);
                System.gc();  //清理内存 鬼知道有没有效果
            }
            else Toast.makeText(MainActivity.this,"错误，上次连接未正常结束",Toast.LENGTH_SHORT).show();  //未正常运行，报个错
        }finally {
            lock_start.unlock();
        }
    }

    private void endAudioStreamReceiver(){
        lock_start.lock();
        try {
            if(linkWinAudio != null){
                linkWinAudio.stop_run();
                linkWinAudio = null;
                ImageView imageView = (ImageView) findViewById(R.id.imageView2);
                imageView.setImageResource(R.drawable.unlink);
            }
        }finally {
            lock_start.unlock();
        }

    }

    public boolean isCorrectIp(String ipString) {
        String ipRegex = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";	//IP地址的正则表达式
        //如果前三项判断都满足，就判断每段数字是否都位于0-255之间
        if (ipString.matches(ipRegex)) {
            String[] ipArray = ipString.split("\\.");
            for (String s : ipArray) {
                int number = Integer.parseInt(s);
                //4.判断每段数字是否都在0-255之间
                if (number < 0 || number > 255) {
                    return false;
                }
            }
            return true;
        }
        else {
            return false;
        }
    }


}
