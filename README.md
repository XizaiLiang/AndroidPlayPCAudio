# AndroidPlayPCAudio

AndroidPlayPCAudio是一个简易的Android应用程序，用于在安卓手机上播放电脑的音频。该项目使用VS2022编写了一个C++小项目，包含以下文件说明：

- **ComputerSpeakers**：一个纯Java的Android应用程序，用于接收通过socket传输的音频数据。代码可能不太规范，因为作者在面向对象编程方面没有掌握得很精通。
  
- **PYSocketSendAudio.py**：主要用于通过socket在安卓设备和本地服务器之间传输音频数据，并根据参数调整音频格式和大小。

- **CppSocketSendAudio**：一个C++的简易程序，用于监听当前电脑播放的声音并通过socket发送。与PYSocketSendAudio.py的功能相同，但速度似乎更快。该程序已经带有编译好的文件。

这个项目的目的是实现安卓手机播放电脑音频的功能，其中涉及到安卓应用开发、Python脚本编写以及C++程序编写和编译。
