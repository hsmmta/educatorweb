package com.demo.tts;  // 确认你的包名

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class SpeechHelper {
    private static final Logger logger = LoggerFactory.getLogger(SpeechHelper.class);
    private String appKey;
    private NlsClient client;

    // 请替换为你的真实信息
    private static final String ACCESS_KEY_ID = "你的AccessKey ID";
    private static final String ACCESS_KEY_SECRET = "你的AccessKey Secret";
    private static final String APP_KEY = "你的Appkey";

    public SpeechHelper() {
        this.appKey = APP_KEY;
        String url = "wss://nls-gateway.cn-shanghai.aliyuncs.com/ws/v1";
        try {
            AccessToken accessToken = new AccessToken(ACCESS_KEY_ID, ACCESS_KEY_SECRET);
            accessToken.apply();
            logger.info("Token获取成功: {}", accessToken.getToken());
            client = new NlsClient(url, accessToken.getToken());
        } catch (Exception e) {
            logger.error("初始化失败", e);
            throw new RuntimeException(e);
        }
    }

    // 创建监听器，只实现我们需要的方法，其他方法空实现（但不加 @Override 以避免不匹配）
    private SpeechSynthesizerListener createListener() {
        return new SpeechSynthesizerListener() {
            private File f = new File("tts_output.wav");
            private FileOutputStream fout;
            private boolean firstRecvBinary = true;

            {
                try {
                    fout = new FileOutputStream(f);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            // 以下所有方法都是 SpeechSynthesizerListener 接口中定义的，签名完全匹配 SDK 2.2.11
            @Override
            public void onStart(SpeechSynthesizerResponse response) {
                logger.info("开始合成");
            }

            @Override
            public void onSynthesisStart(SpeechSynthesizerResponse response) {
                logger.info("合成开始");
            }

            @Override
            public void onSynthesisComplete(SpeechSynthesizerResponse response) {
                logger.info("合成完成");
            }

            @Override
            public void onMessage(ByteBuffer message) {
                try {
                    if (firstRecvBinary) {
                        firstRecvBinary = false;
                        logger.info("收到第一包数据");
                    }
                    byte[] bytes = new byte[message.remaining()];
                    message.get(bytes);
                    fout.write(bytes);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onComplete(SpeechSynthesizerResponse response) {
                logger.info("语音合成结束，状态码: {}, 文件: {}", response.getStatus(), f.getAbsolutePath());
                try {
                    fout.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                logger.error("合成失败，状态码: {}, 消息: {}", response.getStatus(), response.getStatusText());
            }

            @Override
            public void onClose(SpeechSynthesizerResponse response) {
                logger.info("连接关闭");
            }

            @Override
            public void onTaskFailed(String taskId) {
                logger.error("任务失败, taskId: {}", taskId);
            }
        };
    }

    public void synthesize(String text) {
        SpeechSynthesizer synthesizer = null;
        try {
            synthesizer = new SpeechSynthesizer(client, createListener());
            synthesizer.setAppKey(appKey);
            synthesizer.setFormat(OutputFormatEnum.WAV);
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            synthesizer.setVoice("siyue");
            synthesizer.setText(text);
            synthesizer.start();
            synthesizer.waitForComplete();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (synthesizer != null) {
                synthesizer.close();
            }
        }
    }

    public void shutdown() {
        if (client != null) {
            client.shutdown();
        }
    }

    public static void main(String[] args) {
        SpeechHelper helper = new SpeechHelper();
        helper.synthesize("你好，欢迎测试阿里云语音合成。");
        helper.shutdown();
    }
}