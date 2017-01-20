chromium-net-independent

chromium-net-independent是一个独立的网络库，它包含了Chromium中网络相关的代码，包括net库，url库，SSL库，以及Android平台的Java封装等等。同时它还是一个跨平台库，可以应用于Android等移动平台上。

chromium-net-independent 库的优势：
 * 全平台支持最新版TLS。不像OkHttp这样依赖系统提供SSL/TLS加解密功能的网络库，chromium-net-independent自身包含SSL库，因而可以全平台支持安全性更高的最新版TLS。

* 全平台支持HTTP/2及QUIC等最新的网络协议。HTTP/2本身对TLS的版本有要求，同样由于内含SSL库，而可以全平台支持HTTP/2。

在Linux平台上构建
-----------------

1. Clone 当本仓库
   ```
   $ git clone https://github.com/hanpfei/chromium-net-independent.git
   $ cd chromium-net-independent
   ```

2. 如果你是第一次构建，则安装依赖：
   ```
   $ ./build/install-build-deps.sh
   ```

3. 配置编译环境

   本repo包含两个branch，分别为master和cronet。master branch的代码可以分别编译出net等独立的共享库。cornet branch的代码则可以用于编译    chromium net android 平台的封装cronet。
   要想编译cronet，则首先切换至cronet branch。然后编辑out/Default/args.gn文件，依据自己本地的环境，修改android_sdk_root指向自己本地Android SDK的安装目录；修改android_ndk_root指向自己本地的NDK目录（要求NDK版本为R10）；可以根据需要配置target_cpu。

4. 构建Cronet
   ```
   $ gn gen out/Default/
   $ ninja -C out/Default/ cronet
   $ ninja -C out/Default/ cronet_java
   ```
5. 导入二进制文件
   像通常使用第三方Java库那样，将如下的jar文件导入Android工程：
   ```
   out/Default/lib.java/base/base_java.jar
   out/Default/lib.java/components/cronet/android/cronet_api.jar
   out/Default/lib.java/components/cronet/android/cronet_java.jar
   out/Default/lib.java/net/android/net_java.jar
   out/Default/lib.java/url/url_java.jar
   ```

   像通常使用第三方共享库文件那样，导入如下共享库文件：

   ```
   out/Default/libcronet.so
   ```

在Android工程的Java代码中使用Chromium net库。
