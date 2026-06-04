package com.demo.tts;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerListener;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizerParam;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

public class AlibabaTTSDemo {

    private static final String ACCESS_KEY_ID = "你的AccessKey ID";
    private static final String ACCESS_KEY_SECRET = "你的AccessKey Secret";
    private static final String APP_KEY = "你的Appkey";

    public static void main(String[] args) throws Exception {
        NlsClient client = new NlsClient(ACCESS_KEY_ID, ACCESS_KEY_SECRET);
        client.init();

        SpeechSynthesizer synthesizer = null;
        try {
            synthesizer = new SpeechSynthesizer(client, getSynthesizerListener());
            SpeechSynthesizerParam param = SpeechSynthesizerParam.builder()
                    .appKey(APP_KEY)
                    .text("你好，欢迎学习智能语音合成。")
                    .voice("xiaoyun")  // 设置发音人
                    .format("wav")     // 设置音频格式
                    .sampleRate(16000) // 设置采样率
                    .build();
            synthesizer.start(param);
            synthesizer.waitForComplete();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (synthesizer != null) {
                synthesizer.close();
            }
            client.shutdown();
        }
    }

    private static SpeechSynthesizerListener getSynthesizerListener() {
        return new SpeechSynthesizerListener() {
            StringBuilder result = new StringBuilder();

            @Override
            public void onComplete(SpeechSynthesizerResponse response) {
                System.out.println("语音合成完成，状态码：" + response.getStatus());
            }

            @Override
            public void onMessage(ByteBuffer message) {
                try {
                    byte[] voiceData = new byte[message.remaining()];
                    message.get(voiceData);
                    // 播放音频
                    playAudio(voiceData);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onFail(SpeechSynthesizerResponse response) {
                System.out.println("语音合成失败，错误码：" + response.getStatus());
            }
            // 为了清晰，省略了 onStart, onSynthesisStart, onSynthesisComplete, onClose 等方法
        };
    }

    private static void playAudio(byte[] audioData) {
        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(audioData))) {
            Clip clip = AudioSystem.getClip();
            clip.open(audioStream);
            clip.start();
            Thread.sleep(clip.getMicrosecondLength() / 1000);
            clip.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}