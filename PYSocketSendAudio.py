import json
import socket
import threading
import time

import numpy as np
import pyaudiowpatch as pyaudio
import scipy


class AudioSendAndroid:
    def __init__(self):
        # # 配置socket
        self.default_speakers = None
        self.conn = None
        self.audio = None
        self.CHUNK = 1024
        self.HOST = '0.0.0.0'
        self.PORT = 5000
        self.is_KeyboardInterrupt = False
        self.send_time = time.time()

    def init_audio(self):
        audio = pyaudio.PyAudio()
        self.audio = audio
        wasapi_info = {}
        try:
            wasapi_info = audio.get_host_api_info_by_type(pyaudio.paWASAPI)
        except OSError:
            print("Looks like WASAPI is not available on the system. Exiting...")
            exit()

        default_speakers = audio.get_device_info_by_index(wasapi_info["defaultOutputDevice"])
        if not default_speakers["isLoopbackDevice"]:
            for loopback in audio.get_loopback_device_info_generator():
                """
                Try to find loopback device with same name(and [Loopback suffix]).
                Unfortunately, this is the most adequate way at the moment.
                """
                if default_speakers["name"] in loopback["name"]:
                    default_speakers = loopback
                    break
            else:
                print("Default loopback output device not found.\n\nRun `python -m pyaudiowpatch` to check available "
                      "devices.\nExiting...\n")
                exit()
        # print(f"Recording from: ({default_speakers['index']}){default_speakers['name']}")
        print(f"采样率(rate):{default_speakers['defaultSampleRate']},通道(channels):{default_speakers['maxInputChannels']}")
        self.default_speakers = default_speakers
        stream = audio.open(format=pyaudio.paInt16,
                            channels=default_speakers["maxInputChannels"],
                            rate=int(default_speakers["defaultSampleRate"]),
                            frames_per_buffer=self.CHUNK,
                            input=True,
                            input_device_index=default_speakers["index"])
        return stream

    def run(self):
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server_socket.bind((self.HOST, self.PORT))
        server_socket.listen(1)
        print("等待安卓设备连接...")
        # 等待连接
        conn, addr = server_socket.accept()
        print("已连接：", addr)
        self.conn = conn
        data_recv = conn.recv(1024)
        message_dict = json.loads(data_recv.decode().rsplit('\x00')[0])
        rate_int = int(message_dict.get('rate'))
        channels = int(message_dict.get('channels'))
        chunk = int(message_dict.get('chunk'))
        username = message_dict.get('name')

        print(message_dict)
        if rate_int and channels and chunk and username:
            conn.sendall("ok".encode())
            time.sleep(2)
            print(f"连接成功，用户{username}.....")
        else:
            return
        self.CHUNK = chunk
        stream = self.init_audio()
        self.send_test()

        try:
            if channels == self.default_speakers['maxInputChannels'] and rate_int == \
                    self.default_speakers['defaultSampleRate']:
                while True:
                    data = stream.read(self.CHUNK)
                    conn.sendall(data)
            else:
                while True:
                    data = stream.read(self.CHUNK)
                    resampled_data = self.audio_resize(data, rate_int, channels)
                    conn.sendall(resampled_data)
                    break

        except KeyboardInterrupt:
            print("连接关闭.")
            conn.close()
            stream.stop_stream()
            stream.close()
            self.audio.terminate()
            self.is_KeyboardInterrupt = True

    def audio_resize(self, audio_data, rate_int, channels):
        audio_data = np.frombuffer(audio_data, dtype=np.int16)
        sample_rate_ratio = rate_int / self.default_speakers['defaultSampleRate']
        if channels < 2 and self.default_speakers['maxInputChannels'] > 1:
            audio_data = np.mean(np.reshape(audio_data, (-1, 2)), axis=1).astype(np.int16)
        resampled_data = scipy.signal.resample(audio_data, int(len(audio_data) * sample_rate_ratio))
        resampled_data = resampled_data.astype(np.int16)
        resampled_data = resampled_data.tobytes()
        return resampled_data

    def send_test(self):
        def sendData(self_):
            while True:
                self_.conn.recv(1, socket.MSG_OOB)
                time.sleep(1)

        th = threading.Thread(target=sendData, args=(self,))
        th.start()


if __name__ == '__main__':
    audio_send_android = AudioSendAndroid()
    print(socket.gethostname())
    print("可连接的ip地址", socket.gethostbyname_ex(socket.gethostname())[2])
    while True:
        if audio_send_android.is_KeyboardInterrupt:
            break
        try:
            audio_send_android.run()
        except ConnectionError:
            print("断开连接")
