// CppSocketSendAudio.cpp : 此文件包含 "main" 函数。程序执行将在此处开始并结束。
//

#include <iostream>
#include <stdio.h>

#include <math.h>
#include <thread>
#include <csignal>


#include "portaudio/portaudio.h"
#include "portaudio/pa_win_wasapi.h"
#include "json/json.h"
#include "libsamplerate/samplerate.h"


#pragma comment(lib,"ws2_32.lib")
#include <WinSock2.h>
#include <WS2tcpip.h>




static int audioCallback(const void* inputBuffer, void* outputBuffer,
    unsigned long framesPerBuffer,
    const PaStreamCallbackTimeInfo* timeInfo,
    PaStreamCallbackFlags statusFlags,
    void* userData) {
    // Audio processing logic goes here

    return paContinue;
}



Json::Value StringToJsonValue(char* buffer) {
    Json::CharReaderBuilder reader;
    Json::Value parsedRoot;
    std::string error;
    std::istringstream jsonStream(buffer);
    Json::parseFromStream(reader, jsonStream, &parsedRoot, &error);
    return parsedRoot;
}


class socketServer {
public:
    const char* socket_ip = "0.0.0.0";
    int socket_port = 5000;
    int isExit = 0;

    std::string run() {
        /*
            获取电脑音频流，对音频流通过socket发送到客户端
        */
        //初始化windows socket
        WSADATA wsaData;
        if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
            return "Failed to initiialize Winsock";

        }

        //初始化pa_init
        PaError err;
        err = Pa_Initialize();
        if (err != paNoError) {
            return "PortAudio error: ";
        }

        SOCKET serverSocket = socket(AF_INET, SOCK_STREAM, 0);
        if (serverSocket == INVALID_SOCKET) {
            WSACleanup();
            return "Error creating socket";
        }

        //定义服务器地址和端口
        sockaddr_in serverAddr;
        serverAddr.sin_family = AF_INET;
        serverAddr.sin_port = htons(socket_port);
        inet_pton(AF_INET, socket_ip, &serverAddr.sin_addr.s_addr);
        //绑定服务器和地址
        if (bind(serverSocket, (sockaddr*)&serverAddr, sizeof(serverAddr)) == SOCKET_ERROR) {
            closesocket(serverSocket);
            WSACleanup();
            return "Error binding socket";
        }
        //开始监听
        if (listen(serverSocket, 5) == SOCKET_ERROR) {
            closesocket(serverSocket);
            WSACleanup();
            return "Error listening";
        }

        getIPAddress();

        sockaddr_in clientAddr;
        int clientAddrLen = sizeof(clientAddr);
        SOCKET clientSocket = accept(serverSocket, (sockaddr*)&clientAddr, &clientAddrLen);
        std::cout << clientAddr.sin_port << std::endl;
        if (clientSocket == INVALID_SOCKET) {
            closesocket(serverSocket);
            closesocket(clientSocket);
            WSACleanup();
            return "Error accepting client";
        }


        //获取手机传入音频信息
        char buffer[1024];
        int bytesRead = recv(clientSocket, buffer, sizeof(buffer), 0);
        Json::Value messageJson = NULL;
        if (bytesRead > 0) {
            buffer[bytesRead - 1] = '\0';
            std::cout << "Received: " << buffer << std::endl;
            messageJson = StringToJsonValue(buffer);
        }
        if (messageJson != NULL &&
            !messageJson["name"].asString().empty() &&
            !messageJson["channels"].asString().empty() &&
            !messageJson["chunk"].asString().empty() &&
            !messageJson["rate"].asString().empty()) {

            std::this_thread::sleep_for(std::chrono::seconds(2));
            send(clientSocket, "ok", 2, 0);
            printf("连接成功，用户{username}.....");
        }
        else {
            closesocket(serverSocket);
            closesocket(clientSocket);
            WSACleanup();
            printf("连接失败");
            return "userdata error";
        }

        //心跳检测是否断开连接
        std::thread t([this, clientSocket]() {
            testConnection(clientSocket, &isExit);
            });


        //获取电脑音频信息
        int input_device_index = -1;
        input_device_index = get_loopblack_device_index();

        PaStream* stream = NULL;
        PaStreamParameters* outputParameters = NULL;
        PaStreamParameters* inputParameters = NULL;

        const int num_bytes = std::stoi(messageJson["chunk"].asString());

        inputParameters = (PaStreamParameters*)malloc(sizeof(PaStreamParameters));
        if (input_device_index < 0) {
            inputParameters->device = Pa_GetDefaultInputDevice();
        }
        else {
            inputParameters->device = input_device_index;
        }

        if (inputParameters->device < 0) {
            closesocket(serverSocket);
            closesocket(clientSocket);
            WSACleanup();
            Pa_Terminate();
            return "error input_device_index < 0";
        }
        //获取到音频deice
        PaDeviceInfo* _info_deice = (PaDeviceInfo*)Pa_GetDeviceInfo(input_device_index);
        inputParameters->channelCount = _info_deice->maxInputChannels;
        inputParameters->sampleFormat = paInt16;
        inputParameters->suggestedLatency = _info_deice->defaultLowInputLatency;
        inputParameters->hostApiSpecificStreamInfo = NULL;

        int stream_err = -1;
        stream_err = Pa_OpenStream(&stream,
            inputParameters,
            outputParameters,
            _info_deice->defaultSampleRate,
            num_bytes,
            paClipOff,
            NULL,
            NULL);



        stream_err = Pa_StartStream(stream);
        if (stream_err != paNoError) {
            closesocket(serverSocket);
            closesocket(clientSocket);
            Pa_CloseStream(stream);
            Pa_Terminate();
            WSACleanup();
            return "PortAudio error: " + (std::string)Pa_GetErrorText(stream_err);
        }


        Pa_Sleep(1000);

        int readStream = 0;

        const double UserRate = std::stod(messageJson["rate"].asString());
        const double PcRate = _info_deice->defaultSampleRate;

        const int UserChannel = std::stod(messageJson["channels"].asString());
        const int PcChannel = inputParameters->channelCount;


        if (UserRate != PcRate && UserChannel != PcChannel) {
            //音频重采样和声道转换
            int RaAudioArraySize = (num_bytes)*PcChannel;
            short* RaGetAudioData = new short[RaAudioArraySize];

            int ReChannelArraySize = (num_bytes)*UserChannel;
            short* ReChannelAudioData = new short[ReChannelArraySize];

            float* ShortToFloatAudio = new float[ReChannelArraySize];
            int SamplerateAudioSize = (num_bytes)*UserChannel * (UserRate / PcRate);
            float* SamplerateAudioData = new float[SamplerateAudioSize];
            short* FloatToShortAudio = new short[SamplerateAudioSize];

            SRC_DATA srcData;
            srcData.input_frames = num_bytes;
            srcData.output_frames = num_bytes;
            srcData.src_ratio = UserRate / PcRate;

            SRC_STATE* srcState = src_new(SRC_ZERO_ORDER_HOLD, UserChannel, nullptr);

            while (isExit == 0) {
                readStream = Pa_ReadStream(stream, RaGetAudioData, num_bytes);
                if (readStream == paNoError) {
                    ReChannels(RaGetAudioData, ReChannelAudioData, RaAudioArraySize, UserChannel, PcChannel);

                    src_short_to_float_array(ReChannelAudioData, ShortToFloatAudio, ReChannelArraySize);
                    srcData.data_in = ShortToFloatAudio;
                    srcData.data_out = SamplerateAudioData;
                    src_process(srcState, &srcData);

                    src_float_to_short_array(SamplerateAudioData, FloatToShortAudio, SamplerateAudioSize);
                    send(clientSocket, reinterpret_cast<char*>(FloatToShortAudio), SamplerateAudioSize * 2, 0);
                }
            }
            src_delete(srcState);
            closesocket(serverSocket);
            closesocket(clientSocket);
            Pa_CloseStream(stream);
            Pa_Terminate();
            WSACleanup();
        }
        else if (UserRate == PcRate && UserChannel != PcChannel) {
            //不需要重采样，声道需要转换
            int RaAudioArraySize = (num_bytes)*PcChannel;
            short* RaGetAudioData = new short[RaAudioArraySize];

            int ReChannelArraySize = (num_bytes)*UserChannel;
            short* ReChannelAudioData = new short[ReChannelArraySize];
            while (isExit == 0) {
                readStream = Pa_ReadStream(stream, RaGetAudioData, num_bytes);
                if (readStream == paNoError) {
                    ReChannels(RaGetAudioData, ReChannelAudioData, RaAudioArraySize, UserChannel, PcChannel);
                    send(clientSocket, reinterpret_cast<char*>(ReChannelAudioData), ReChannelArraySize * 2, 0);
                }
            }
            closesocket(serverSocket);
            closesocket(clientSocket);
            Pa_CloseStream(stream);
            Pa_Terminate();
            WSACleanup();
        }
        else if (UserRate == PcRate && UserChannel == PcChannel) {
            //获取到的音频直接send
            int RaAudioArraySize = (num_bytes)*PcChannel;
            short* RaGetAudioData = new short[RaAudioArraySize];

            int sendAudioSize = RaAudioArraySize * 2;
            while (isExit == 0) {
                readStream = Pa_ReadStream(stream, RaGetAudioData, num_bytes);
                if (readStream == paNoError) {
                    send(clientSocket, reinterpret_cast<char*>(RaGetAudioData), sendAudioSize, 0);
                    //std::cout << send_return << std::endl;
                }
            }
            closesocket(serverSocket);
            closesocket(clientSocket);
            Pa_CloseStream(stream);
            Pa_Terminate();
            WSACleanup();
        }
        else if (UserRate != PcRate && UserChannel == PcChannel) {
            //需要重采样，声道不变
            int RaAudioArraySize = (num_bytes)*PcChannel;
            short* RaGetAudioData = new short[RaAudioArraySize];

            float* ShortToFloatAudio = new float[RaAudioArraySize];
            int SamplerateAudioSize = (num_bytes)*UserChannel * (UserRate / PcRate);
            float* SamplerateAudioData = new float[SamplerateAudioSize];
            short* FloatToShortAudio = new short[SamplerateAudioSize];

            SRC_DATA srcData;
            srcData.input_frames = num_bytes;
            srcData.output_frames = num_bytes;
            srcData.src_ratio = UserRate / PcRate;

            SRC_STATE* srcState = src_new(SRC_ZERO_ORDER_HOLD, UserChannel, nullptr);


            while (isExit == 0) {
                readStream = Pa_ReadStream(stream, RaGetAudioData, num_bytes);
                if (readStream == paNoError) {
                    src_short_to_float_array(RaGetAudioData, ShortToFloatAudio, RaAudioArraySize);
                    srcData.data_in = ShortToFloatAudio;
                    srcData.data_out = SamplerateAudioData;
                    src_process(srcState, &srcData);
                    src_float_to_short_array(SamplerateAudioData, FloatToShortAudio, SamplerateAudioSize);
                    send(clientSocket, reinterpret_cast<char*>(FloatToShortAudio), SamplerateAudioSize * 2, 0);
                }
            }
            src_delete(srcState);
            closesocket(serverSocket);
            closesocket(clientSocket);
            Pa_CloseStream(stream);
            Pa_Terminate();
            WSACleanup();
        }
        t.join();
        return "exit";
    }

    void ReChannels(short* inputAudio, short* outputAudio, int inputAudioLen, int outputChannels, int inputChannels) {
        int index = 0;
        if (inputChannels == 2 && outputChannels == 1) {
            //电脑音频双声道，返回单声道
            for (int i = 0; i < inputAudioLen; i += 2) {
                outputAudio[index] = (short)(inputAudio[i] + inputAudio[i + 1]) / 2.0f;
                index += 1;
            }
        }

        else if (inputChannels == 1 && outputChannels == 2) {
            //电脑音频单声道，返回双声道
            for (int i = 0; i < inputAudioLen; i++) {
                outputAudio[index] = inputAudio[i];
                outputAudio[index + 1] = inputAudio[i];
                index += 2;
            }

        }
    }

    int get_loopblack_device_index() {
        PaHostApiIndex apiIndex = Pa_HostApiTypeIdToHostApiIndex(paWASAPI);
        PaHostApiInfo* _info_Host = (PaHostApiInfo*)Pa_GetHostApiInfo(apiIndex);

        printf("defaultInputDevice:%d\n", _info_Host->defaultInputDevice);
        printf("defaultOutputDevice:%d\n", _info_Host->defaultOutputDevice);
        printf("deviceCount:%d\n", _info_Host->deviceCount);
        printf("name:%s\n", _info_Host->name);
        printf("structVersion:%d\n", _info_Host->structVersion);
        printf("type:%d\n", _info_Host->type);
        PaDeviceInfo* _info_deice = (PaDeviceInfo*)Pa_GetDeviceInfo(_info_Host->defaultOutputDevice);
        printf("name:%s\n", _info_deice->name);

        int input_device_index = -1;

        for (int i = 0; i < _info_Host->deviceCount; i++) {
            PaDeviceIndex devIndex = Pa_HostApiDeviceIndexToDeviceIndex(apiIndex, i);
            if (PaWasapi_IsLoopback(devIndex) == 1) {
                PaDeviceInfo* _info_deice_loopback = (PaDeviceInfo*)Pa_GetDeviceInfo(devIndex);
                std::string _info_deice_loopback_name = _info_deice_loopback->name;
                std::string _info_deice_name = _info_deice->name;
                size_t founPos_deice_name = _info_deice_loopback_name.find(_info_deice_name);
                if (founPos_deice_name != std::string::npos) {
                    printf("name:%s;", _info_deice_loopback->name);
                    printf("rate:%f;", _info_deice_loopback->defaultSampleRate);
                    printf("channels:%d\n", _info_deice_loopback->maxInputChannels);
                    input_device_index = devIndex;
                    break;
                }

            }
        }
        printf("input_device_index:%d\n", input_device_index);
        return input_device_index;
    }

    void testConnection(SOCKET clientSocket, int* isExit) {
        char buffer[1];
        while (*isExit == 0) {
            if (clientSocket != NULL) {
                int bytesRead = recv(clientSocket, buffer, 1, MSG_OOB);
                if (bytesRead == -1) {
                    std::cout << "断开连接" << std::endl;
                    *isExit = 1;
                    break;
                }
            }
            Pa_Sleep(1000);
        }
    }

    void getIPAddress() {
        char hostname[255] = "";
        if (gethostname(hostname, sizeof(hostname)) == SOCKET_ERROR) {
            std::cout << "无法获取计算机主机名" << std::endl;
            return;
        }

        struct addrinfo hints, * result, * rp;
        memset(&hints, 0, sizeof(struct addrinfo));
        hints.ai_family = AF_INET; // IPv4
        hints.ai_socktype = SOCK_STREAM; // 使用流式套接字

        int status = getaddrinfo(hostname, NULL, &hints, &result);
        if (status != 0) {
            std::cerr << "getaddrinfo 错误: " << gai_strerror(status) << std::endl;
            return;
        }

        std::cout << "可连接ip:   ";
        for (rp = result; rp != NULL; rp = rp->ai_next) {
            struct sockaddr_in* addr = (struct sockaddr_in*)rp->ai_addr;
            char ipBuffer[INET_ADDRSTRLEN];
            const char* result = inet_ntop(AF_INET, &addr->sin_addr, ipBuffer, sizeof(ipBuffer));
            std::cout << ipBuffer << " , ";
        }
        std::cout << std::endl;
        std::cout << "连接port:   " << socket_port << std::endl;
        freeaddrinfo(result);
    }

};




//// 信号处理函数
//void signalHandler(int signum) {
//    if (signum == SIGINT) {
//        std::cout << "Ctrl+C pressed. Exiting..." << std::endl;
//        socket_servet.isExit = 1;
//    }
//}


int main()
{

    //signal(SIGINT, signalHandler);
    socketServer socket_servet;
    while (true)
    {
        socket_servet.isExit = 0;
        std::cout << socket_servet.run() << std::endl;
        Pa_Sleep(1000);
        std::cout << "重新连接" << std::endl;
    }
    return 1;
}
